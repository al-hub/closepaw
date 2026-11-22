import struct
import tempfile
import unittest
import zipfile
from pathlib import Path
from scripts.check_native_page_size import check_elf, check_apk

def elf(align=0x4000):
    buf = bytearray(256)
    buf[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<Q", buf, 32, 64)
    struct.pack_into("<HH", buf, 54, 56, 1)
    struct.pack_into("<IIQQQQQQ", buf, 64, 1, 5, 0, 0, 0, 128, 128, align)
    return bytes(buf)

class NativePageSizeTest(unittest.TestCase):
    def test_elf16_pass(self):
        self.assertEqual([], check_elf(elf(), "pass.so"))

    def test_elf4_fails(self):
        self.assertTrue(check_elf(elf(0x1000), "fail.so"))

    def test_invalid_elf_fails(self):
        self.assertTrue(check_elf(b"bad", "fail.so"))

    def test_compressed_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp) / "app.apk"
            with zipfile.ZipFile(apk, "w", zipfile.ZIP_DEFLATED) as z:
                z.writestr("lib/arm64-v8a/libgood.so", elf())
            self.assertEqual((1, []), check_apk(str(apk)))

    def test_unaligned_stored_entry_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp) / "app.apk"
            with zipfile.ZipFile(apk, "w", zipfile.ZIP_STORED) as z:
                z.writestr("lib/arm64-v8a/libgood.so", elf())
            self.assertTrue(check_apk(str(apk))[1])
