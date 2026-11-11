#import <Foundation/Foundation.h>
#import <CoreImage/CoreImage.h>
#import <CoreText/CoreText.h>
#include "wifi.hpp"

namespace phonecam::wifi {
namespace {
void drawText(CGContextRef context, const std::string &text, CGFloat x, CGFloat y, CGFloat size, bool bold, CGFloat gray) {
    CTFontRef font = CTFontCreateUIFontForLanguage(bold ? kCTFontUIFontEmphasizedSystem : kCTFontUIFontSystem, size, nullptr);
    CGColorRef color = CGColorCreateGenericGray(gray, 1);
    NSDictionary *attributes = @{(__bridge id)kCTFontAttributeName: (__bridge id)font, (__bridge id)kCTForegroundColorAttributeName: (__bridge id)color};
    NSAttributedString *string = [[NSAttributedString alloc] initWithString:@(text.c_str()) attributes:attributes];
    CTLineRef line = CTLineCreateWithAttributedString((__bridge CFAttributedStringRef)string);
    CGContextSetTextPosition(context, x, y);
    CTLineDraw(line, context);
    CFRelease(line); CGColorRelease(color); CFRelease(font);
}
}

// Dark card, QR code on the left (black on white with a quiet zone, nearest-neighbour scaled so it
// stays sharp), steps and the typed code on the right. Core Graphics' origin is bottom-left.
std::vector<uint8_t> renderPairingCard(const PairingWindow &window, uint32_t width, uint32_t height) {
    @autoreleasepool {
        std::vector<uint8_t> pixels(size_t(width) * height * 4);
        CGColorSpaceRef space = CGColorSpaceCreateDeviceRGB();
        CGContextRef context = CGBitmapContextCreate(pixels.data(), width, height, 8, width * 4, space,
                                                     kCGImageAlphaPremultipliedFirst | kCGBitmapByteOrder32Little);
        CGColorSpaceRelease(space);
        if (!context) throw std::runtime_error("Could not draw the pairing card");
        CGContextSetRGBFillColor(context, 0.06, 0.09, 0.16, 1);
        CGContextFillRect(context, CGRectMake(0, 0, width, height));

        CIFilter *filter = [CIFilter filterWithName:@"CIQRCodeGenerator"];
        [filter setValue:[@(window.link.c_str()) dataUsingEncoding:NSUTF8StringEncoding] forKey:@"inputMessage"];
        [filter setValue:@"M" forKey:@"inputCorrectionLevel"];
        CIImage *qr = filter.outputImage;
        if (qr) {
            CIContext *ci = [CIContext contextWithOptions:@{kCIContextUseSoftwareRenderer: @YES}];
            CGImageRef image = [ci createCGImage:qr fromRect:qr.extent];
            CGFloat side = height * 0.72, margin = height * 0.14;
            CGFloat quiet = side * 0.06;
            CGContextSetRGBFillColor(context, 1, 1, 1, 1);
            CGContextFillRect(context, CGRectMake(margin, margin, side, side));
            CGContextSetInterpolationQuality(context, kCGInterpolationNone);
            CGContextDrawImage(context, CGRectMake(margin + quiet, margin + quiet, side - 2 * quiet, side - 2 * quiet), image);
            CGImageRelease(image);
        }
        CGFloat x = height * 0.14 * 2 + height * 0.72, top = height * 0.80, s = height / 720.0;
        drawText(context, "Pair a phone over Wi-Fi", x, top, 40 * s, true, 1);
        drawText(context, "1. On the phone, open PhoneCam → Settings", x, top - 70 * s, 24 * s, false, 0.85);
        drawText(context, "2. Tap Pair a computer and point it here", x, top - 110 * s, 24 * s, false, 0.85);
        drawText(context, "    or tap Type a pairing code and enter:", x, top - 150 * s, 24 * s, false, 0.85);
        drawText(context, window.code, x, top - 230 * s, 40 * s, true, 1);
        drawText(context, "Works for 3 minutes. Hide this source from your stream", x, top - 310 * s, 20 * s, false, 0.65);
        drawText(context, "while pairing: anyone who sees the code could pair.", x, top - 340 * s, 20 * s, false, 0.65);
        CGContextRelease(context);
        return pixels;
    }
}
}
