import SwiftUI
import MIMKit

/// 登录后的落地页：本机身份指纹 + 账号下的设备。
///
/// M1 先只读不写（不做注销）；确认 Keychain 与登录链路在真机上稳定后再加操作。
struct DeviceListView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        List {
            Section("本机身份") {
                VStack(alignment: .leading, spacing: 6) {
                    Text("设备标识").font(.caption).foregroundStyle(.secondary)
                    Text(model.deviceId).font(.footnote.monospaced())
                }
                VStack(alignment: .leading, spacing: 6) {
                    Text("身份指纹").font(.caption).foregroundStyle(.secondary)
                    Text(model.identityFingerprint.isEmpty ? "（尚未生成）" : model.identityFingerprint)
                        .font(.footnote.monospaced())
                    Text("和桌面端「我的设备」页比对同一账号的指纹，不一致说明不是同一台设备。")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }

            Section("设备（\(model.devices.count)）") {
                ForEach(model.devices, id: \.deviceId) { device in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(device.deviceName ?? device.deviceId)
                            if device.deviceId == model.deviceId {
                                Text("本机").font(.caption2)
                                    .padding(.horizontal, 6).padding(.vertical, 2)
                                    .background(.tint.opacity(0.15), in: Capsule())
                            }
                        }
                        Text(device.deviceId).font(.caption.monospaced()).foregroundStyle(.secondary)
                    }
                }
            }

            if let error = model.error {
                Section { Text(error).foregroundStyle(.red).font(.footnote) }
            }

            Section {
                Button("刷新设备") { Task { await model.loadDevices() } }
                Button("退出登录", role: .destructive) { model.logout() }
            }
        }
        .navigationTitle("我的设备")
        .refreshable { await model.loadDevices() }
    }
}
