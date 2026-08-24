#!/usr/bin/env python3
"""Undine 앱 아이콘 생성기.

아이콘을 바이너리로만 커밋하면 "왜 이 모양인지" 와 "어떻게 다시 만드는지" 가 사라진다.
이 스크립트가 마스터 PNG 를 그리고, OS 별 포맷(icns/ico/png)까지 만든다.

    python3 packaging/make-icons.py

의존성 없이 표준 라이브러리만 쓴다 (PIL·ImageMagick 을 요구하지 않는다).
icns 변환은 macOS 의 `sips`·`iconutil` 을 쓰며, 없으면 그 단계만 건너뛴다.

모양: 어두운 라운드 사각형 위에 물방울(Undine = 물의 정령) 하나와, 그 안에 커밋 두 개를 잇는
선 하나. 16px 로 줄여도 물방울 실루엣이 남도록 장식을 넣지 않았다.

색은 디자인 토큰의 다크 팔레트에서 가져왔다 (ColorTokens.Dark 의 background·accent·foreground).
"""

from __future__ import annotations

import os
import struct
import subprocess
import sys
import zlib

MASTER = 1024
SUPERSAMPLE = 2

BACKGROUND = (0x12, 0x16, 0x1C)
ACCENT = (0x7F, 0xB4, 0xFF)
FOREGROUND = (0xEE, 0xF1, 0xF5)

HERE = os.path.dirname(os.path.abspath(__file__))
ICON_PNG = os.path.join(HERE, "undine.png")
ICON_ICNS = os.path.join(HERE, "undine.icns")
ICON_ICO = os.path.join(HERE, "undine.ico")
ICONSET = os.path.join(HERE, "undine.iconset")

# ICO 에 담을 크기. Windows 탐색기가 고르는 대표 크기들이다.
ICO_SIZES = (16, 32, 48, 64, 128, 256)
# icns 가 요구하는 이름 규칙: icon_<w>x<h>[@2x].png
ICNS_ENTRIES = (
    ("icon_16x16.png", 16),
    ("icon_16x16@2x.png", 32),
    ("icon_32x32.png", 32),
    ("icon_32x32@2x.png", 64),
    ("icon_128x128.png", 128),
    ("icon_128x128@2x.png", 256),
    ("icon_256x256.png", 256),
    ("icon_256x256@2x.png", 512),
    ("icon_512x512.png", 512),
    ("icon_512x512@2x.png", 1024),
)


def rounded_rect(x: float, y: float, size: float, radius: float) -> bool:
    """정사각형 캔버스를 채우는 라운드 사각형 안쪽인지."""
    inner = size - radius
    cx = min(max(x, radius), inner)
    cy = min(max(y, radius), inner)
    return (x - cx) ** 2 + (y - cy) ** 2 <= radius**2


def droplet(x: float, y: float, size: float) -> bool:
    """물방울 — 아래쪽 원과 위쪽 삼각형을 합친 실루엣."""
    center_x = size * 0.5
    bulb_y = size * 0.62
    bulb_r = size * 0.24
    if (x - center_x) ** 2 + (y - bulb_y) ** 2 <= bulb_r**2:
        return True
    tip_y = size * 0.20
    if y < tip_y or y > bulb_y:
        return False
    # 꼭짓점에서 원 접점까지 선형으로 벌어지는 삼각형.
    spread = (y - tip_y) / (bulb_y - tip_y) * bulb_r
    return abs(x - center_x) <= spread


def commit_line(x: float, y: float, size: float) -> bool:
    """물방울 안의 커밋 두 개와 이음선."""
    center_x = size * 0.5
    top = size * 0.52
    bottom = size * 0.72
    node_r = size * 0.045
    if (x - center_x) ** 2 + (y - top) ** 2 <= node_r**2:
        return True
    if (x - center_x) ** 2 + (y - bottom) ** 2 <= node_r**2:
        return True
    return abs(x - center_x) <= size * 0.014 and top <= y <= bottom


def sample(x: float, y: float, size: float) -> tuple[int, int, int, int]:
    """한 점의 색. 바깥은 투명이다 — OS 가 자기 모양으로 마스킹하지 않는다."""
    if not rounded_rect(x, y, size, size * 0.22):
        return (0, 0, 0, 0)
    if droplet(x, y, size):
        if commit_line(x, y, size):
            return (*BACKGROUND, 255)
        return (*ACCENT, 255)
    return (*BACKGROUND, 255)


def render(size: int) -> bytes:
    """RGBA 픽셀. 경계는 supersampling 으로 부드럽게 만든다."""
    step = 1.0 / SUPERSAMPLE
    rows = bytearray()
    for py in range(size):
        for px in range(size):
            acc = [0, 0, 0, 0]
            for sy in range(SUPERSAMPLE):
                for sx in range(SUPERSAMPLE):
                    color = sample(px + (sx + 0.5) * step, py + (sy + 0.5) * step, size)
                    for channel in range(4):
                        acc[channel] += color[channel]
            count = SUPERSAMPLE * SUPERSAMPLE
            rows.extend(bytes(value // count for value in acc))
    return bytes(rows)


def png(size: int, pixels: bytes) -> bytes:
    """RGBA 픽셀을 PNG 로 인코딩한다 (필터 0, 한 IDAT)."""
    raw = bytearray()
    stride = size * 4
    for row in range(size):
        raw.append(0)
        raw.extend(pixels[row * stride:(row + 1) * stride])

    def chunk(tag: bytes, payload: bytes) -> bytes:
        return (
            struct.pack(">I", len(payload))
            + tag
            + payload
            + struct.pack(">I", zlib.crc32(tag + payload) & 0xFFFFFFFF)
        )

    header = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + chunk(b"IEND", b"")
    )


def write_png(path: str, size: int) -> None:
    with open(path, "wb") as handle:
        handle.write(png(size, render(size)))
    print(f"  {os.path.relpath(path, HERE)} ({size}px)")


def write_ico(path: str) -> None:
    """PNG 를 담은 ICO. Vista 이후 Windows 가 PNG 항목을 읽는다."""
    images = [(size, png(size, render(size))) for size in ICO_SIZES]
    offset = 6 + 16 * len(images)
    directory = bytearray(struct.pack("<HHH", 0, 1, len(images)))
    for size, data in images:
        # 256 은 0 으로 적는다 (1바이트 필드).
        dimension = 0 if size == 256 else size
        directory.extend(
            struct.pack("<BBBBHHII", dimension, dimension, 0, 0, 1, 32, len(data), offset)
        )
        offset += len(data)
    with open(path, "wb") as handle:
        handle.write(bytes(directory))
        for _, data in images:
            handle.write(data)
    print(f"  {os.path.relpath(path, HERE)} ({len(images)} sizes)")


def write_icns(master: str) -> None:
    """`sips` 로 크기별 PNG 를 만들고 `iconutil` 로 icns 를 만든다 (macOS 전용)."""
    if not (os.path.exists("/usr/bin/sips") and os.path.exists("/usr/bin/iconutil")):
        print("  icns 건너뜀 — sips/iconutil 이 없는 환경")
        return
    os.makedirs(ICONSET, exist_ok=True)
    for name, size in ICNS_ENTRIES:
        subprocess.run(
            ["/usr/bin/sips", "-z", str(size), str(size), master, "--out", os.path.join(ICONSET, name)],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    subprocess.run(["/usr/bin/iconutil", "-c", "icns", ICONSET, "-o", ICON_ICNS], check=True)
    for name, _ in ICNS_ENTRIES:
        os.remove(os.path.join(ICONSET, name))
    os.rmdir(ICONSET)
    print(f"  {os.path.relpath(ICON_ICNS, HERE)}")


def main() -> int:
    print("Undine 아이콘 생성")
    write_png(ICON_PNG, MASTER)
    write_ico(ICON_ICO)
    write_icns(ICON_PNG)
    return 0


if __name__ == "__main__":
    sys.exit(main())
