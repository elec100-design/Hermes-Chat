import SwiftUI
import Security

struct SettingsView: View {
    @ObservedObject var appSettings: AppSettings
    @State private var testResult: String? = nil
    @State private var isTesting = false
    @State private var showApiKeyInput = false

    var body: some View {
        Form {
            Section("Hermes 연결") {
                TextField("Server Host", text: $appSettings.serverHost)
                    .textContentType(.URL)
                    .keyboardType(.URL)
                HStack {
                    if showApiKeyInput {
                        TextField("API Key", text: $appSettings.apiKey)
                    } else {
                        SecureField("API Key", text: $appSettings.apiKey)
                    }
                    Button {
                        showApiKeyInput.toggle()
                    } label: {
                        Image(systemName: showApiKeyInput ? "eye" : "eye.slash")
                    }
                    .buttonStyle(.plain)
                }
                Button("연결 테스트") {
                    testConnection()
                }
                .disabled(isTesting)

                if let testResult {
                    Text(testResult)
                        .font(.footnote)
                        .foregroundStyle(testResult.contains("성공") ? .green : .red)
                }
            }

            Section("기본 모델") {
                TextField("Model", text: $appSettings.selectedModel)
            }

            Section {
                Link("Tailscale 다운로드", destination: URL(string: "https://tailscale.com")!)
            }
        }
        .navigationTitle("설정")
        .navigationBarTitleDisplayMode(.large)
    }

    private func testConnection() {
        guard let url = URL(string: "\(appSettings.serverHost)/health") else {
            testResult = "Invalid Host"
            return
        }

        isTesting = true
        testResult = nil

        var request = URLRequest(url: url)
        if !appSettings.apiKey.isEmpty {
            request.setValue(appSettings.apiKey, forHTTPHeaderField: "Authorization")
        }

        Task {
            do {
                let (_, response) = try await URLSession.shared.data(for: request)
                if let http = response as? HTTPURLResponse {
                    testResult = http.statusCode == 200 ? "연결 성공" : "상태 코드: \(http.statusCode)"
                } else {
                    testResult = "응답 형식 오류"
                }
            } catch {
                testResult = "연결 실패: \(error.localizedDescription)"
            }
            isTesting = false
        }
    }
}
