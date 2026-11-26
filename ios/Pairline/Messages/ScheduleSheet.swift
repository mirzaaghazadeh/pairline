import SwiftUI

/// "Send later…" (D-084): pick when the bridge phone sends the message. It keeps the message, so it goes at that time
/// even if this iPhone is off or away.
struct ScheduleSheet: View {
    /// Whether the bridge can send at the exact minute (Android's "Alarms & reminders").
    let exact: Bool
    let onSchedule: (Date) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var date = ScheduleSheet.defaultDate()

    private static func defaultDate() -> Date {
        // The next full hour, at least ten minutes away.
        let soon = Date().addingTimeInterval(10 * 60)
        let calendar = Calendar.current
        let hour = calendar.dateInterval(of: .hour, for: soon)?.end ?? soon
        return hour
    }

    private var range: ClosedRange<Date> {
        Date().addingTimeInterval(60)...Date().addingTimeInterval(365 * 24 * 3600)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    DatePicker(selection: $date, in: range, displayedComponents: [.date, .hourAndMinute]) {
                        Text("Send at", tableName: "Extras")
                    }
                    .datePickerStyle(.graphical)
                } footer: {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Your Android phone keeps the message and sends it at this time, even if this iPhone is off or away. Touch and hold it in the conversation to cancel.", tableName: "Extras")
                        if !exact {
                            Text("Android may send it a few minutes late: allow Pairline Bridge under Alarms & reminders on the Android phone for the exact minute.", tableName: "Extras")
                                .foregroundStyle(Color.brandAmber)
                        }
                    }
                }
            }
            .navigationTitle(Text("Send later", tableName: "Extras"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        onSchedule(max(date, Date().addingTimeInterval(60)))
                    } label: {
                        Text("Schedule", tableName: "Extras")
                    }
                }
            }
        }
        .presentationDetents([.large])
    }
}
