"""Repack the existing PNG representations from AppIcon.icns into a Windows ICO.

Run without arguments to preview; pass --apply to write the bundled icon.
"""
import argparse
from pathlib import Path
import struct


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    icons = Path(__file__).resolve().parents[1] / "app/src/jvmMain/resources/icons"
    source = (icons / "AppIcon.icns").read_bytes()
    assert source[:4] == b"icns" and struct.unpack_from(">I", source, 4)[0] == len(source)
    images = {}
    position = 8
    while position < len(source):
        length = struct.unpack_from(">I", source, position + 4)[0]
        assert length >= 8 and position + length <= len(source)
        png = source[position + 8:position + length]
        if png.startswith(b"\x89PNG\r\n\x1a\n"):
            width, height = struct.unpack_from(">II", png, 16)
            if width == height and width in (16, 32, 64, 128, 256):
                images[width] = png
        position += length
    assert set(images) == {16, 32, 64, 128, 256}
    directory = bytearray(struct.pack("<HHH", 0, 1, len(images)))
    payload = bytearray()
    offset = 6 + 16 * len(images)
    for size, png in sorted(images.items()):
        directory.extend(struct.pack("<BBBBHHII", size % 256, size % 256, 0, 0,
                                     1, 32, len(png), offset + len(payload)))
        payload.extend(png)
    target = icons / "AppIcon.ico"
    print(f"{'Write' if args.apply else 'Would write'} {target}: 16/32/64/128/256 px, {len(directory) + len(payload)} bytes")
    if args.apply:
        temporary = target.with_suffix(".ico.tmp")
        temporary.write_bytes(directory + payload)
        temporary.replace(target)


if __name__ == "__main__":
    main()
