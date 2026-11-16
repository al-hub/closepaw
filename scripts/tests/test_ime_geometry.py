import types
from pathlib import Path
import unittest
from unittest.mock import patch
import struct


path = Path(__file__).resolve().parents[2] / 'app/src/main/assets/control/ime_geometry.py'
# Compile in memory so running CI tests cannot add bytecode to packaged assets.
geometry = types.ModuleType('ime_geometry')
exec(compile(path.read_bytes(), str(path), 'exec'), geometry.__dict__)

# Synthetic native-format fixtures: no live dumps or user data in the repository.
A = '''A11yWindow[AccessibilityWindowInfo[displayId=0, id=2, type=TYPE_INPUT_METHOD, region=SkRegion((0,1798,1440,3120))]]
WindowInfo[WindowInfo[displayId=0, type=2011, token=android.os.BinderProxy@abc, region=SkRegion((0,1798,1440,3120)), mTransformMatrix=Matrix{[1.0, 0.0, 0.0][0.0, 1.0, 127.0][0.0, 0.0, 1.0]}, mMagnificationSpec=<scale:1.0,offsetX:0.0,offsetY:0.0>]]'''
W = '''  Window #0 Window{def u0 InputMethod}:
 mClient=android.os.BinderProxy@abc
 mDisplayId=0 mAttrs=ty=INPUT_METHOD mDisplayRotation=ROTATION_0
 mHasSurface=true isReadyForDisplay()=true Surface: shown=true
 Requested w=1440 h=2993
 mGivenContentInsets=[0,1671][0,0]
 Frames: parent=[0,127][1440,3120] frame=[0,127][1440,3120]
 mSurface=Surface(name=VRI-InputMethod$_123#456)/@0x789
'''
S = '''  Layer [456] VRI-InputMethod$_123#456
 toDisplayTransform={ tx=0.0000 ty=127.0000 }
+ LayerFE (VRI-InputMethod$_123#456)
 layerStack=0 isSecure=false geomLayerTransform (ROT_0) (TRANSLATE)
 geomBufferSize=[0 0 1440 2993] geomContentCrop=[0 0 1440 2993] geomBufferTransform=0
  - Output Layer 0xabc(VRI-InputMethod$_123#456)
 displayFrame=[0 127 1440 3120] sourceCrop=[0.000000 0.000000 1440.000000 2993.000000] bufferTransform=0
'''


class ImeGeometryTest(unittest.TestCase):
    def parse(self, a=A, w=W, s=S):
        return geometry.parse(a, w, s, 2, [0, 1798, 1440, 3120])

    def test_native_identity_join_proves_translation_without_using_content_inset(self):
        p = self.parse()
        self.assertEqual([0, 127], p['translation'])
        self.assertEqual([0, 127, 1440, 3120], p['frame'])
        self.assertEqual(2993, p['height'])

    def test_no_fixed_portrait_offset(self):
        a = A.replace('127.0', '32.0').replace('3120', '3025')
        w = W.replace('127', '32').replace('3120', '3025')
        s = S.replace('127', '32').replace('3120', '3025')
        p = geometry.parse(a, w, s, 2, [0, 1798, 1440, 3025])
        self.assertEqual([0, 32], p['translation'])

    def test_unknown_or_ambiguous_evidence_refuses(self):
        cases = [
            (A.replace('id=2,', 'id=3,'), W, S),
            (A + '\n' + A, W, S),
            (A.replace('WindowInfo[WindowInfo[', 'unrelated\nWindowInfo[WindowInfo['), W, S),
            (A.replace('token=android.os.BinderProxy@abc', 'token=android.os.BinderProxy@aaa'), W, S),
            (A, W + W, S),
            (A.replace('1.0, 0.0, 0.0', '2.0, 0.0, 0.0'), W, S),
            (A.replace('scale:1.0', 'scale:2.0'), W, S),
            (A, W.replace('ROTATION_0', 'ROTATION_1'), S),
            (A, W.replace('mHasSurface=true', 'mHasSurface=false'), S),
            (A, W.replace(' Surface: shown=true', ' Surface: shown=false'), S),
            (A, W.replace('Requested w=1440', 'Requested w=1441'), S),
            (A, W + ' surfaceInsets=[0,1][0,0]', S),
            (A, W.replace('#456', '#457'), S),
            (A, W, S.replace('geomBufferSize=[0 0 1440 2993]', 'geomBufferSize=[0 0 1440 2992]')),
            (A, W, S.replace('(TRANSLATE)', '(SCALE)')),
            (A, W, S.replace('sourceCrop=[0.000000', 'sourceCrop=[1.000000')),
            (A, W, S.replace('bufferTransform=0', 'bufferTransform=1')),
            (A, W, S.replace('displayFrame=[0 127', 'displayFrame=[0 128')),
            (A, W, S.replace('ty=127.0000', 'ty=128.0000')),
            (A, W, S + S[S.index('  - Output Layer'):]),
        ]
        for i, args in enumerate(cases):
            with self.subTest(i=i), self.assertRaises(geometry.Refused):
                self.parse(*args)

    def test_main_never_connects_or_pairs_and_has_no_caller_shell(self):
        calls = []
        original = geometry.run
        def run(argv, limit=4000000):
            calls.append(argv)
            if argv == ['adb', 'devices']:
                return 'List of devices attached\nfixture:12345\tdevice\n'
            command = argv[4:]
            if command[0] == 'sha256sum':
                return 'a' * 64 + '  ' + command[1] + '\n'
            if command[0] == 'settings': return 'ime/.Keyboard\n'
            return {('dumpsys', 'accessibility'): A, ('dumpsys', 'window', 'windows'): W,
                    ('dumpsys', 'SurfaceFlinger'): S}[tuple(command)]
        try:
            geometry.run = run
            import contextlib, io, json
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                geometry.main(['2', '0', '1798', '1440', '3120',
                    '/storage/emulated/0/Android/data/ai.closepaw/files/ime-geometry/abc-123.nonce', 'ime/.Keyboard'])
            self.assertEqual('a' * 64, json.loads(out.getvalue())['challenge_sha256'])
            self.assertTrue(all('connect' not in c and 'pair' not in c for c in calls))
        finally:
            geometry.run = original

    def test_multiple_devices_refuse_before_any_device_command(self):
        original = geometry.run
        try:
            geometry.run = lambda argv: 'List of devices attached\none\tdevice\ntwo\tdevice\n'
            with self.assertRaises(geometry.Refused):
                geometry.main(['2', '0', '1798', '1440', '3120',
                    '/storage/emulated/0/Android/data/ai.closepaw/files/ime-geometry/abc.nonce', 'ime/.Keyboard'])
        finally:
            geometry.run = original

    def test_smart_socket_reads_fragmented_shell_frames_and_never_starts_a_server(self):
        class Connection:
            def __init__(self, data): self.data, self.sent = data, []
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def settimeout(self, value): pass
            def sendall(self, value): self.sent.append(value)
            def recv(self, count):
                data, self.data = self.data[:min(count,3)], self.data[min(count,3):]
                return data
        def packet(stream, payload): return struct.pack('<BI',stream,len(payload))+payload
        connection = Connection(b'OKAYOKAY'+packet(1,b'example\n')+packet(3,b'\x00'))
        with patch.object(geometry.socket,'create_connection',return_value=connection) as connect:
            self.assertEqual('example\n',geometry.run(['adb','-s','fixture:123','shell','getprop','ro.product.model']))
            connect.assert_called_once_with(('127.0.0.1',5037),timeout=0.4)
        self.assertEqual([b'001ahost:transport:fixture:123',b'0025shell,v2,raw:getprop ro.product.model'],connection.sent)
        for bad in [packet(3,b'\x01'),packet(2,b'error'),packet(9,b''),b'\x01\xff\xff\xff\x7f']:
            with self.subTest(bad=bad), patch.object(geometry.socket,'create_connection',return_value=Connection(b'OKAYOKAY'+bad)):
                with self.assertRaises(geometry.Refused):
                    geometry.run(['adb','-s','fixture:123','shell','getprop','ro.product.model'])
        with patch.object(geometry.socket,'create_connection',side_effect=ConnectionRefusedError) as connect:
            with self.assertRaises(ConnectionRefusedError): geometry.run(['adb','devices'])
            self.assertEqual(1,connect.call_count)


if __name__ == '__main__':
    unittest.main()
