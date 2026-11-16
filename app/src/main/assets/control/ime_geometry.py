"""Read-only, fail-closed authority for an already-connected local Android IME.

Raw system dumps remain invocation-local. Only the joined numeric geometry and
ephemeral challenge hash leave this process. Never connects, pairs or changes ADB.
"""
import hashlib
import json
import re
import socket
import shlex
import struct
import sys


class Refused(Exception):
    pass


def require(value):
    if not value:
        raise Refused()


def one(pattern, text, flags=0):
    values = re.findall(pattern, text, flags)
    require(len(values) == 1)
    return values[0]


def rectangle(value):
    return [int(x) for x in value]


def parse(accessibility, windows, compositor, window_id, touch, ime_pid=None):
    # AccessibilityWindowManager emits WindowInfo immediately after A11yWindow.
    lines = accessibility.splitlines()
    indices = [i for i, s in enumerate(lines)
               if re.search(r'\bid=' + str(window_id) + r',', s)
               and 'A11yWindow[AccessibilityWindowInfo[' in s]
    require(len(indices) == 1)
    i = indices[0]
    a = lines[i]
    require('type=TYPE_INPUT_METHOD,' in a and 'displayId=0,' in a)
    require(i + 1 < len(lines) and lines[i + 1].lstrip().startswith('WindowInfo[WindowInfo['))
    info = lines[i + 1]
    require('type=2011,' in info and 'displayId=0,' in info)
    require(rectangle(one(r'region=SkRegion\(\((-?\d+),(-?\d+),(-?\d+),(-?\d+)\)\)', a)) == touch)
    require(rectangle(one(r'region=SkRegion\(\((-?\d+),(-?\d+),(-?\d+),(-?\d+)\)\)', info)) == touch)
    token = one(r'\btoken=(android\.os\.BinderProxy@[0-9a-f]+),', info)
    matrix = one(r'mTransformMatrix=Matrix\{\[([^]]+)\]\[([^]]+)\]\[([^]]+)\]\}', info)
    matrix = [[float(v.strip()) for v in row.split(',')] for row in matrix]
    require(len(matrix) == 3 and all(len(row) == 3 for row in matrix))
    require(matrix[0][:2] == [1.0, 0.0] and matrix[1][:2] == [0.0, 1.0]
            and matrix[2] == [0.0, 0.0, 1.0])
    tx, ty = matrix[0][2], matrix[1][2]
    require(tx.is_integer() and ty.is_integer() and 0 <= tx <= 8192 and 0 <= ty <= 8192)
    tx, ty = int(tx), int(ty)
    require('mMagnificationSpec=<scale:1.0,offsetX:0.0,offsetY:0.0>' in info)
    # Join native Binder identity, not a window title or a dimensions heuristic.
    blocks = re.split(r'(?m)^  Window #', windows)[1:]
    matches = [b for b in blocks if re.search(r'\bmClient=' + re.escape(token) + r'(?:\s|$)', b)]
    require(len(matches) == 1)
    w = matches[0]
    require('ty=INPUT_METHOD' in w and 'mDisplayId=0 ' in w and 'mHasSurface=true' in w
            and 'isReadyForDisplay()=true' in w and 'Surface: shown=true' in w)
    require('mDisplayRotation=ROTATION_0' in w)
    frame = rectangle(one(r'Frames:.*? frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]', w))
    require(frame[:2] == [tx, ty])
    width, height = frame[2] - tx, frame[3] - ty
    require(0 < width <= 8192 and 0 < height <= 8192 and width * height <= 8000000)
    require(rectangle(one(r'Requested w=(\d+) h=(\d+)', w)) == [width, height])
    # Explicit nonzero Surface insets are unsupported. Content insets are a
    # different quantity and must NEVER be added to the surface origin.
    for inset in re.findall(r'surfaceInsets=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]', w):
        require(rectangle(inset) == [0, 0, 0, 0])
    surface = one(r'mSurface=Surface\(name=(VRI-InputMethod\$_(\d+)#(\d+))\)/', w)
    name, pid, layer = surface
    if ime_pid is not None:
        require(int(pid) == ime_pid)
    require(tx <= touch[0] < touch[2] <= frame[2] and ty <= touch[1] < touch[3] <= frame[3])
    # Use the precise WM surface ID and all matching compositor outputs. More
    # than one output is ambiguous (including mirroring), so refuse it.
    output = re.split(r'(?m)^  - Output Layer ', compositor)[1:]
    output = [b for b in output if b.splitlines()[0].endswith('(' + name + ')')]
    require(len(output) == 1)
    o = output[0].split('\n  - Output Layer ', 1)[0]
    display_frame = rectangle(one(r'(?<!override )\bdisplayFrame=\[(-?\d+) (-?\d+) (-?\d+) (-?\d+)\]', o))
    crop = [float(v) for v in one(r'\bsourceCrop=\[([\d.]+) ([\d.]+) ([\d.]+) ([\d.]+)\]', o)]
    require(display_frame == frame and crop == [0.0, 0.0, float(width), float(height)])
    require(one(r'\bbufferTransform=(\d+)', o) == '0')
    # LayerFE sections are exact-name delimited; never spill into another layer.
    fe = re.split(r'(?m)^\+ LayerFE ', compositor)[1:]
    fe = [b for b in fe if b.splitlines()[0] == '(' + name + ')']
    require(len(fe) == 1)
    f = fe[0]
    require('geomLayerTransform (ROT_0) (TRANSLATE)' in f
            or (tx == 0 and ty == 0 and 'geomLayerTransform (ROT_0) (IDENTITY)' in f))
    require('layerStack=0 isSecure=false' in f)
    require(rectangle(one(r'geomBufferSize=\[(-?\d+) (-?\d+) (-?\d+) (-?\d+)\]', f)) == [0, 0, width, height])
    require(rectangle(one(r'geomContentCrop=\[(-?\d+) (-?\d+) (-?\d+) (-?\d+)\]', f)) == [0, 0, width, height])
    require(one(r'geomBufferTransform=(\d+)', f) == '0')
    # Flattened layer snapshot independently agrees with WindowInfo's matrix.
    snapshot = re.split(r'(?m)^  Layer \[', compositor)[1:]
    snapshot = [b for b in snapshot if b.splitlines()[0] == layer + '] ' + name]
    require(snapshot)
    for b in snapshot:
        transform = one(r'toDisplayTransform=\{([^}]+)\}', b)
        vals = re.fullmatch(r'\s*tx=([-\d.]+) ty=([-\d.]+)\s*', transform)
        require(vals and [float(x) for x in vals.groups()] == [float(tx), float(ty)])
    return {'window_id': window_id, 'token': token, 'surface': name,
            'frame': frame, 'touch': touch, 'width': width, 'height': height,
            'translation': [tx, ty], 'rotation': 0}


def receive(connection, count):
    require(0 <= count <= 4000000)
    out = bytearray()
    while len(out) < count:
        chunk = connection.recv(count - len(out))
        require(chunk)
        out.extend(chunk)
    return bytes(out)


def request(connection, service):
    payload = service.encode('utf-8')
    require(0 < len(payload) <= 4096)
    connection.sendall(('%04x' % len(payload)).encode('ascii') + payload)
    require(receive(connection, 4) == b'OKAY')


def run(argv, limit=4000000):
    # Connect ONLY to the existing local smart socket. Unlike invoking the adb
    # executable, this protocol cannot start, restart or upgrade the host server.
    with socket.create_connection(('127.0.0.1', 5037), timeout=0.4) as connection:
        connection.settimeout(1.5)
        if argv == ['adb', 'devices']:
            request(connection, 'host:devices')
            size = int(receive(connection, 4), 16)
            require(size <= 16384)
            return 'List of devices attached\n' + receive(connection, size).decode('utf-8', errors='strict')
        require(len(argv) >= 6 and argv[:2] == ['adb', '-s'] and argv[3] == 'shell')
        require(re.fullmatch(r'[A-Za-z0-9_.:\[\]-]{1,160}', argv[2]))
        request(connection, 'host:transport:' + argv[2])
        request(connection, 'shell,v2,raw:' + shlex.join(argv[4:]))
        output = bytearray()
        # shell_v2 packets: one-byte stream id, little-endian uint32 size.
        while True:
            stream, size = struct.unpack('<BI', receive(connection, 5))
            require(size <= limit and len(output) + size <= limit)
            payload = receive(connection, size)
            if stream == 1:
                output.extend(payload)
            elif stream == 2:
                require(not payload)
            elif stream == 3:
                require(payload == b'\x00')
                return output.decode('utf-8', errors='strict')
            else:
                raise Refused()


def main(argv):
    require(len(argv) == 7)
    window_id = int(argv[0])
    touch = [int(x) for x in argv[1:5]]
    path, method = argv[5:7]
    require(0 <= window_id <= 2147483647)
    require(re.fullmatch(r'/storage/emulated/\d+/Android/data/ai\.closepaw/files/ime-geometry/[a-f0-9-]+\.nonce', path))
    require(re.fullmatch(r'[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+', method))
    rows = run(['adb', 'devices']).splitlines()[1:]
    devices = [row.split() for row in rows if row.strip()]
    require(len(devices) == 1 and len(devices[0]) == 2 and devices[0][1] == 'device')
    serial = devices[0][0]
    require(re.fullmatch(r'[A-Za-z0-9_.:\[\]-]{1,160}', serial))
    def shell(*args):
        return run(['adb', '-s', serial, 'shell', *args])
    challenge = shell('sha256sum', path).split()
    require(len(challenge) == 2 and challenge[1] == path and re.fullmatch('[a-f0-9]{64}', challenge[0]))
    require(shell('settings', 'get', 'secure', 'default_input_method').strip() == method)
    a = shell('dumpsys', 'accessibility')
    w = shell('dumpsys', 'window', 'windows')
    s = shell('dumpsys', 'SurfaceFlinger')
    result = parse(a, w, s, window_id, touch)
    require(shell('sha256sum', path).split() == challenge)
    result.update(schema=1, challenge_sha256=challenge[0], serial=serial)
    print(json.dumps(result, sort_keys=True, separators=(',', ':')))


if __name__ == '__main__':
    try:
        main(sys.argv[1:])
    except Exception:
        # No raw dumps, paths, identities, screen text or subprocess diagnostics.
        print('{"error":"ime_geometry_unverified","schema":1}')
        sys.exit(1)
