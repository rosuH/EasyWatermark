import PhotosUI
import SwiftUI

/// Main-photo picker host (ADR-0029 P1 / owner A).
///
/// SwiftUI `.photosPicker` has no `preselectedAssetIdentifiers` on the current SDK.
/// UIKit `PHPickerConfiguration` does — only when created with `photoLibrary`.
/// Icon watermark picker stays on SwiftUI `.photosPicker`.
struct PhotoLibraryPHPicker: UIViewControllerRepresentable {
    var preselectedAssetIdentifiers: [String]
    var onFinish: ([PHPickerResult]) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onFinish: onFinish)
    }

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var configuration = PHPickerConfiguration(photoLibrary: .shared())
        configuration.filter = .images
        configuration.selectionLimit = 50
        configuration.preferredAssetRepresentationMode = .current
        configuration.preselectedAssetIdentifiers = preselectedAssetIdentifiers.filter { !$0.isEmpty }
        let picker = PHPickerViewController(configuration: configuration)
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: PHPickerViewController, context: Context) {}

    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        let onFinish: ([PHPickerResult]) -> Void

        init(onFinish: @escaping ([PHPickerResult]) -> Void) {
            self.onFinish = onFinish
        }

        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            onFinish(results)
        }
    }
}
