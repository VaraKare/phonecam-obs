// Windows pairing card: the same layout as the Mac's (pairing_card.mm), drawn with GDI. The QR code comes
// from Project Nayuki's QR Code generator (MIT, fetched at a pinned version by tools/build_windows.py).
#include "wifi.hpp"
#include <qrcodegen.hpp>
#include <algorithm>

namespace phonecam::wifi {
namespace {
std::wstring wide(const std::string &text) {
    if (text.empty()) return {};
    int size = MultiByteToWideChar(CP_UTF8, 0, text.data(), int(text.size()), nullptr, 0);
    std::wstring out(size_t(size), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, text.data(), int(text.size()), out.data(), size);
    return out;
}
// Draws one line with its baseline at y (pixels from the top).
void drawText(HDC dc, const std::string &text, int x, int y, int size, bool bold, int gray) {
    HFONT font = CreateFontW(-size, 0, 0, 0, bold ? FW_SEMIBOLD : FW_NORMAL, FALSE, FALSE, FALSE, DEFAULT_CHARSET, OUT_DEFAULT_PRECIS,
                             CLIP_DEFAULT_PRECIS, ANTIALIASED_QUALITY, DEFAULT_PITCH, L"Segoe UI");
    HGDIOBJ previous = SelectObject(dc, font);
    SetTextColor(dc, RGB(gray, gray, gray));
    const std::wstring line = wide(text);
    TextOutW(dc, x, y, line.c_str(), int(line.size()));
    SelectObject(dc, previous);
    DeleteObject(font);
}
}

// Dark card, QR code on the left (black on white with a quiet zone, whole pixels per module so it stays
// sharp), steps and the typed code on the right.
std::vector<uint8_t> renderPairingCard(const PairingWindow &window, uint32_t width, uint32_t height) {
    BITMAPINFO info{};
    info.bmiHeader.biSize = sizeof(info.bmiHeader);
    info.bmiHeader.biWidth = LONG(width); info.bmiHeader.biHeight = -LONG(height);  // top-down
    info.bmiHeader.biPlanes = 1; info.bmiHeader.biBitCount = 32; info.bmiHeader.biCompression = BI_RGB;
    void *bits = nullptr;
    HDC dc = CreateCompatibleDC(nullptr);
    HBITMAP bitmap = dc ? CreateDIBSection(dc, &info, DIB_RGB_COLORS, &bits, nullptr, 0) : nullptr;
    if (!bitmap) { if (dc) DeleteDC(dc); throw std::runtime_error("Could not draw the pairing card"); }
    HGDIOBJ previous = SelectObject(dc, bitmap);
    auto fill = [&](int left, int top, int right, int bottom, COLORREF color) {
        RECT area{left, top, right, bottom};
        HBRUSH brush = CreateSolidBrush(color);
        FillRect(dc, &area, brush);
        DeleteObject(brush);
    };
    const int H = int(height);
    fill(0, 0, int(width), H, RGB(15, 23, 41));

    const int side = H * 72 / 100, margin = H * 14 / 100, quiet = side * 6 / 100;
    fill(margin, margin, margin + side, margin + side, RGB(255, 255, 255));
    const auto qr = qrcodegen::QrCode::encodeText(window.link.c_str(), qrcodegen::QrCode::Ecc::MEDIUM);
    const int modules = qr.getSize(), cell = std::max(1, (side - 2 * quiet) / modules);
    const int origin = margin + (side - cell * modules) / 2;
    for (int y = 0; y < modules; ++y)
        for (int x = 0; x < modules; ++x)
            if (qr.getModule(x, y)) fill(origin + x * cell, origin + y * cell, origin + (x + 1) * cell, origin + (y + 1) * cell, RGB(0, 0, 0));

    SetBkMode(dc, TRANSPARENT);
    SetTextAlign(dc, TA_LEFT | TA_BASELINE);
    const double s = H / 720.0;
    const int x = margin * 2 + side, top = H - H * 80 / 100;   // the Mac measures baselines from the bottom
    auto at = [&](int offset) { return top + int(offset * s); };
    drawText(dc, "Pair a phone over Wi-Fi", x, at(0), int(40 * s), true, 255);
    drawText(dc, "1. On the phone, open PhoneCam → Settings", x, at(70), int(24 * s), false, 217);
    drawText(dc, "2. Tap Pair a computer and point it here", x, at(110), int(24 * s), false, 217);
    drawText(dc, "    or tap Type a pairing code and enter:", x, at(150), int(24 * s), false, 217);
    drawText(dc, window.code, x, at(230), int(40 * s), true, 255);
    drawText(dc, "Works for 3 minutes. Hide this source from your stream", x, at(310), int(20 * s), false, 166);
    drawText(dc, "while pairing: anyone who sees the code could pair.", x, at(340), int(20 * s), false, 166);
    GdiFlush();

    std::vector<uint8_t> pixels(static_cast<const uint8_t *>(bits), static_cast<const uint8_t *>(bits) + size_t(width) * height * 4);
    for (size_t i = 3; i < pixels.size(); i += 4) pixels[i] = 255;  // GDI leaves alpha at 0
    SelectObject(dc, previous);
    DeleteObject(bitmap);
    DeleteDC(dc);
    return pixels;
}
}
