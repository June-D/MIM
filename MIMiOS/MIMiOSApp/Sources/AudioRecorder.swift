import AVFoundation
import Foundation

/// 语音录制：录成 m4a（AAC），停止时返回字节与时长。
///
/// 麦克风权限是运行时申请的，`NSMicrophoneUsageDescription` 必须写在 Info.plist 里，
/// 否则系统会直接拒绝且不给弹窗——这类问题在模拟器上尤其难查。
@MainActor
final class AudioRecorder {

    private var recorder: AVAudioRecorder?

    /// 正在录制的时长（秒），界面用来显示计时。
    private(set) var startedAt: Date?

    var isRecording: Bool { recorder?.isRecording ?? false }

    /// 请求录音权限。
    ///
    /// `AVAudioApplication` 要 iOS 17，项目最低支持 16，所以按系统版本分两路——
    /// 只写新 API 会在 iOS 16 上直接编译不过，只写旧 API 又会在新系统上告警。
    static func requestPermission() async -> Bool {
        await withCheckedContinuation { continuation in
            if #available(iOS 17.0, *) {
                AVAudioApplication.requestRecordPermission { granted in
                    continuation.resume(returning: granted)
                }
            } else {
                AVAudioSession.sharedInstance().requestRecordPermission { granted in
                    continuation.resume(returning: granted)
                }
            }
        }
    }

    func start() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
        try session.setActive(true)

        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("voice-\(UUID().uuidString).m4a")
        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: 32_000,
            AVNumberOfChannelsKey: 1,
            AVEncoderAudioQualityKey: AVAudioQuality.medium.rawValue,
        ]
        let recorder = try AVAudioRecorder(url: url, settings: settings)
        recorder.record()
        self.recorder = recorder
        self.startedAt = Date()
    }

    /// 停止并返回（字节, 时长毫秒）。没在录音时返回 nil。
    func stop() -> (data: Data, durationMs: Int64)? {
        guard let recorder, recorder.isRecording else { return nil }
        let duration = Int64((startedAt.map { Date().timeIntervalSince($0) } ?? 0) * 1000)
        recorder.stop()
        let url = recorder.url
        self.recorder = nil
        self.startedAt = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        guard let data = try? Data(contentsOf: url) else { return nil }
        try? FileManager.default.removeItem(at: url)
        return (data, max(duration, 300))
    }

    func cancel() {
        recorder?.stop()
        if let url = recorder?.url {
            try? FileManager.default.removeItem(at: url)
        }
        recorder = nil
        startedAt = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}
