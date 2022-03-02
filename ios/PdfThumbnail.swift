import PDFKit

@objc(PdfThumbnail)
class PdfThumbnail: NSObject {

    @objc
    static func requiresMainQueueSetup() -> Bool {
        return false
    }
    
    func getCachesDirectory() -> URL {
        let paths = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)
        return paths[0]
    }
    
    func getOutputFilename(filePath: String, page: Int) -> String {
        let components = filePath.components(separatedBy: "/")
        var prefix: String
        if let origionalFileName = components.last {
            prefix = origionalFileName.replacingOccurrences(of: ".", with: "-")
        } else {
            prefix = "pdf"
        }
        let random = Int.random(in: 0 ..< Int.max)
        return "\(prefix)-thumbnail-\(page)-\(random).png"
    }
    
    func cropToBounds(image: UIImage, width: Double, height: Double) -> UIImage {
            let cgimage = image.cgImage!
            let rect: CGRect = CGRect(x: 0, y: 0, width: width, height: height)
            let imageRef: CGImage = cgimage.cropping(to: rect)!
            let image: UIImage = UIImage(cgImage: imageRef, scale: image.scale, orientation: image.imageOrientation)
            return image
        }
    
    func cropToRedPixel(image: UIImage) -> UIImage {
        guard let cgImage = image.cgImage,
            let data = cgImage.dataProvider?.data,
            let bytes = CFDataGetBytePtr(data) else {
            fatalError("Couldn't access image data")
        }
        assert(cgImage.colorSpace?.model == .rgb)
        let bytesPerPixel = cgImage.bitsPerPixel / cgImage.bitsPerComponent
        
        let midPoint = cgImage.width / 2
        var maxY = cgImage.height
    
        for y in 0 ..< cgImage.height {
            let offset = ((cgImage.height - y - 1) * cgImage.bytesPerRow) + (midPoint * bytesPerPixel)
            let components = (r: bytes[offset], g: bytes[offset + 1], b: bytes[offset + 2])
            if components.r == 0 && components.b == 255 && components.g == 0 {
                NSLog("[y:\(y)] \(components)")
                maxY = cgImage.height - y - 2
            }
            NSLog("[y:\(y)] \(components)")
        }
        
        return cropToBounds(image:image, width: Double(cgImage.width), height:Double(maxY))
    }
  
    func generatePageBase64(pdfPage: PDFPage, page: Int) -> Dictionary<String, Any>? {
        let pageRect = pdfPage.bounds(for: .mediaBox)
        let imageThumb = pdfPage.thumbnail(of: CGSize(width: pageRect.width, height: pageRect.height), for: .mediaBox)
        let image = cropToRedPixel(image: imageThumb)
        guard let data = image.pngData() else {
            return nil
        }
        let base64 = data.base64EncodedString()
        return [
            "base64": base64,
            "width": Int(pageRect.width),
            "height": Int(pageRect.height),
        ]
    }

    func generatePage(pdfPage: PDFPage, filePath: String, page: Int) -> Dictionary<String, Any>? {
        let pageRect = pdfPage.bounds(for: .mediaBox)
        let imageThumb = pdfPage.thumbnail(of: CGSize(width: pageRect.width, height: pageRect.height), for: .mediaBox)
        let image = cropToRedPixel(image: imageThumb)
        let outputFile = getCachesDirectory().appendingPathComponent(getOutputFilename(filePath: filePath, page: page))
      guard let data = image.pngData() else {
            return nil
        }
        do {
            try data.write(to: outputFile)
            return [
                "uri": outputFile.absoluteString,
                "width": Int(pageRect.width),
                "height": Int(pageRect.height),
            ]
        } catch {
            return nil
        }
    }
    
    @available(iOS 11.0, *)
    @objc(generate:withPage:withResolver:withRejecter:)
    func generate(filePath: String, page: Int, resolve:RCTPromiseResolveBlock, reject:RCTPromiseRejectBlock) -> Void {
        guard let fileUrl = URL(string: filePath) else {
            reject("FILE_NOT_FOUND", "File \(filePath) not found", nil)
            return
        }
        guard let pdfDocument = PDFDocument(url: fileUrl) else {
            reject("FILE_NOT_FOUND", "File \(filePath) not found", nil)
            return
        }
        guard let pdfPage = pdfDocument.page(at: page) else {
            reject("INVALID_PAGE", "Page number \(page) is invalid, file has \(pdfDocument.pageCount) pages", nil)
            return
        }

        if let pageResult = generatePage(pdfPage: pdfPage, filePath: filePath, page: page) {
            resolve(pageResult)
        } else {
            reject("INTERNAL_ERROR", "Cannot write image data", nil)
        }
    }
  
    @available(iOS 11.0, *)
    @objc(generateWithBase64:withPage:withResolver:withRejecter:)
    func generateWithBase64(base64: String, page: Int, resolve:RCTPromiseResolveBlock, reject:RCTPromiseRejectBlock) -> Void {
        let data = Data.init(base64Encoded: base64);
        guard let pdfDocument = PDFDocument(data: data!) else {
            reject("FILE_NOT_FOUND", "File \(base64) not found", nil)
            return
        }
        guard let pdfPage = pdfDocument.page(at: page) else {
            reject("INVALID_PAGE", "Page number \(page) is invalid, file has \(pdfDocument.pageCount) pages", nil)
            return
        }

        if let pageResult = generatePageBase64(pdfPage: pdfPage, page: page) {
            resolve(pageResult)
        } else {
            reject("INTERNAL_ERROR", "Cannot write image data", nil)
        }
    }

    @available(iOS 11.0, *)
    @objc(generateAllPages:withResolver:withRejecter:)
    func generateAllPages(filePath: String, resolve:RCTPromiseResolveBlock, reject:RCTPromiseRejectBlock) -> Void {
        guard let fileUrl = URL(string: filePath) else {
            reject("FILE_NOT_FOUND", "File \(filePath) not found", nil)
            return
        }
        guard let pdfDocument = PDFDocument(url: fileUrl) else {
            reject("FILE_NOT_FOUND", "File \(filePath) not found", nil)
            return
        }

        var result: [Dictionary<String, Any>] = []
        for page in 0..<pdfDocument.pageCount {
            guard let pdfPage = pdfDocument.page(at: page) else {
                reject("INVALID_PAGE", "Page number \(page) is invalid, file has \(pdfDocument.pageCount) pages", nil)
                return
            }
            if let pageResult = generatePage(pdfPage: pdfPage, filePath: filePath, page: page) {
                result.append(pageResult)
            } else {
                reject("INTERNAL_ERROR", "Cannot write image data", nil)
                return
            }
        }
        resolve(result)
    }
}

extension UIImage {
    subscript (x: Int, y: Int) -> UIColor? {
        guard x >= 0 && x < Int(size.width) && y >= 0 && y < Int(size.height),
            let cgImage = cgImage,
            let provider = cgImage.dataProvider,
            let providerData = provider.data,
            let data = CFDataGetBytePtr(providerData) else {
            return nil
        }

        let numberOfComponents = 4
        let pixelData = ((Int(size.width) * y) + x) * numberOfComponents

        let r = CGFloat(data[pixelData]) / 255.0
        let g = CGFloat(data[pixelData + 1]) / 255.0
        let b = CGFloat(data[pixelData + 2]) / 255.0
        let a = CGFloat(data[pixelData + 3]) / 255.0

        return UIColor(red: r, green: g, blue: b, alpha: a)
    }
}
