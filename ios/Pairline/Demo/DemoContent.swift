import Foundation

/// Sample data for demo mode (D-020), written for each app language. All names, numbers and companies are made up.
struct DemoContent {
    struct Person {
        let name: String
        let number: String
        let label: String
        let email: String?
        let starred: Bool
    }

    struct Line {
        let fromMe: Bool
        let text: String
        /// Minutes before now.
        let minutesAgo: Int
    }

    struct Conversation {
        /// Index into `people`, or nil for a business sender.
        let person: Int?
        let sender: String
        let lines: [Line]
    }

    struct Call {
        let person: Int?
        let number: String
        let type: String
        let minutesAgo: Int
        let duration: Int
        let sim: Int
    }

    let carriers: (String, String)
    let ownNumber: String
    let people: [Person]
    let conversations: [Conversation]
    let calls: [Call]
    let incomingOTP: (sender: String, text: String)
    let replies: [String]
    let ussdBalance: String
    let ussdMenu: String
    let ussdUnknown: String

    static func current() -> DemoContent {
        let language = Bundle.main.preferredLocalizations.first ?? "en"
        switch language {
        case "fa": return persian
        case "ar": return arabic
        default: return english
        }
    }

    // MARK: - English

    static let english = DemoContent(
        carriers: ("Lumen Mobile", "Orbit"),
        ownNumber: "+1 415 555 0134",
        people: [
            Person(name: "Maya Chen", number: "+1 415 555 0182", label: "mobile", email: "maya@example.com", starred: true),
            Person(name: "Daniel Brooks", number: "+1 628 555 0147", label: "mobile", email: nil, starred: false),
            Person(name: "Mom", number: "+1 415 555 0110", label: "home", email: nil, starred: true),
            Person(name: "Sofia Ramirez", number: "+1 510 555 0199", label: "work", email: "sofia@example.org", starred: false),
            Person(name: "Leo Martins", number: "+1 650 555 0123", label: "mobile", email: nil, starred: false),
            Person(name: "Aisha Khan", number: "+1 408 555 0165", label: "mobile", email: "aisha@example.net", starred: true),
            Person(name: "Ben Walker", number: "+1 925 555 0171", label: "mobile", email: nil, starred: false),
            Person(name: "Dr. Elena Rossi", number: "+1 415 555 0190", label: "work", email: nil, starred: false),
            Person(name: "Grace Liu", number: "+1 707 555 0156", label: "mobile", email: nil, starred: false),
            Person(name: "Omar Haddad", number: "+1 831 555 0138", label: "mobile", email: nil, starred: false),
        ],
        conversations: [
            Conversation(person: 0, sender: "", lines: [
                Line(fromMe: false, text: "Are we still on for dinner tonight?", minutesAgo: 95),
                Line(fromMe: true, text: "Yes! 7:30 at the usual place?", minutesAgo: 92),
                Line(fromMe: false, text: "Perfect. I'll book a table for three, Leo is joining 🎉", minutesAgo: 90),
                Line(fromMe: true, text: "Great, see you there", minutesAgo: 12),
            ]),
            Conversation(person: nil, sender: "NWBANK", lines: [
                Line(fromMe: false, text: "Northwind Bank: Your verification code is 482913. It expires in 10 minutes. Never share it with anyone.", minutesAgo: 3),
            ]),
            Conversation(person: 2, sender: "", lines: [
                Line(fromMe: false, text: "Did you land safely?", minutesAgo: 60 * 5),
                Line(fromMe: true, text: "Just landed. Calling you in a bit ❤️", minutesAgo: 60 * 5 - 20),
                Line(fromMe: false, text: "Call me when you get home", minutesAgo: 60 * 5 - 25),
            ]),
            Conversation(person: nil, sender: "PARCEL", lines: [
                Line(fromMe: false, text: "Your package is out for delivery and will arrive today between 2 and 5 PM. Track: example.com/t/8K2Q", minutesAgo: 60 * 8),
            ]),
            Conversation(person: 3, sender: "", lines: [
                Line(fromMe: false, text: "Could you send me the slides before the meeting?", minutesAgo: 60 * 26),
                Line(fromMe: true, text: "Sending them now", minutesAgo: 60 * 25),
                Line(fromMe: false, text: "Got them, thanks!", minutesAgo: 60 * 25 - 30),
            ]),
            Conversation(person: 1, sender: "", lines: [
                Line(fromMe: false, text: "Game night on Saturday?", minutesAgo: 60 * 50),
                Line(fromMe: true, text: "Count me in. I'll bring snacks", minutesAgo: 60 * 49),
            ]),
            Conversation(person: nil, sender: "ACME-ID", lines: [
                Line(fromMe: false, text: "G-551203 is your Acme sign-in code.", minutesAgo: 60 * 75),
            ]),
            Conversation(person: 5, sender: "", lines: [
                Line(fromMe: true, text: "Happy birthday Aisha! 🎂", minutesAgo: 60 * 24 * 4),
                Line(fromMe: false, text: "Thank you so much!! 😊", minutesAgo: 60 * 24 * 4 - 40),
            ]),
        ],
        calls: [
            Call(person: 2, number: "", type: "in", minutesAgo: 45, duration: 754, sim: 0),
            Call(person: 0, number: "", type: "out", minutesAgo: 130, duration: 212, sim: 0),
            Call(person: nil, number: "+1 800 555 0100", type: "missed", minutesAgo: 60 * 6, duration: 0, sim: 1),
            Call(person: 3, number: "", type: "out", minutesAgo: 60 * 26, duration: 1260, sim: 1),
            Call(person: 4, number: "", type: "missed", minutesAgo: 60 * 30, duration: 0, sim: 0),
            Call(person: 1, number: "", type: "in", minutesAgo: 60 * 52, duration: 95, sim: 0),
            Call(person: nil, number: "+1 213 555 0177", type: "rejected", minutesAgo: 60 * 70, duration: 0, sim: 0),
            Call(person: 7, number: "", type: "out", minutesAgo: 60 * 96, duration: 180, sim: 1),
            Call(person: 5, number: "", type: "in", minutesAgo: 60 * 24 * 4, duration: 620, sim: 0),
        ],
        incomingOTP: ("NWBANK", "Northwind Bank: 739204 is your one-time code to approve the payment. Don't share it."),
        replies: ["Sounds good 👍", "On my way!", "Thanks, talk soon", "Haha, perfect"],
        ussdBalance: "Your balance is $23.40. Data left: 4.2 GB, valid until Dec 31.",
        ussdMenu: "1. Buy a data pack\n2. Check minutes\n3. Roaming\n0. Exit",
        ussdUnknown: "Unknown code. Check the code and try again."
    )

    // MARK: - Persian

    static let persian = DemoContent(
        carriers: ("سپهر همراه", "ماهان‌تل"),
        ownNumber: "+98 912 555 0134",
        people: [
            Person(name: "مریم احمدی", number: "+98 912 555 0182", label: "mobile", email: "maryam@example.com", starred: true),
            Person(name: "علی رضایی", number: "+98 935 555 0147", label: "mobile", email: nil, starred: false),
            Person(name: "مامان", number: "+98 21 5555 0110", label: "home", email: nil, starred: true),
            Person(name: "سارا کریمی", number: "+98 919 555 0199", label: "work", email: "sara@example.org", starred: false),
            Person(name: "رضا محمدی", number: "+98 901 555 0123", label: "mobile", email: nil, starred: false),
            Person(name: "نگار حسینی", number: "+98 912 555 0165", label: "mobile", email: "negar@example.net", starred: true),
            Person(name: "امیر نوری", number: "+98 937 555 0171", label: "mobile", email: nil, starred: false),
            Person(name: "دکتر شیرازی", number: "+98 21 5555 0190", label: "work", email: nil, starred: false),
            Person(name: "پریا صادقی", number: "+98 918 555 0156", label: "mobile", email: nil, starred: false),
            Person(name: "حمید کاظمی", number: "+98 914 555 0138", label: "mobile", email: nil, starred: false),
        ],
        conversations: [
            Conversation(person: 0, sender: "", lines: [
                Line(fromMe: false, text: "سلام! امشب شام هنوز سر جاشه؟", minutesAgo: 95),
                Line(fromMe: true, text: "آره، ساعت ۷:۳۰ همون رستوران همیشگی؟", minutesAgo: 92),
                Line(fromMe: false, text: "عالیه. برای سه نفر میز رزرو می‌کنم، رضا هم میاد 🎉", minutesAgo: 90),
                Line(fromMe: true, text: "عالی، می‌بینمت", minutesAgo: 12),
            ]),
            Conversation(person: nil, sender: "BANK-NEMOONE", lines: [
                Line(fromMe: false, text: "بانک نمونه\nکد تایید شما: ۴۸۲۹۱۳\nاین کد تا ۲ دقیقه معتبر است. آن را در اختیار دیگران قرار ندهید.", minutesAgo: 3),
            ]),
            Conversation(person: 2, sender: "", lines: [
                Line(fromMe: false, text: "رسیدی عزیزم؟", minutesAgo: 60 * 5),
                Line(fromMe: true, text: "تازه نشستیم. یه کم دیگه زنگ می‌زنم ❤️", minutesAgo: 60 * 5 - 20),
                Line(fromMe: false, text: "رسیدی خونه زنگ بزن", minutesAgo: 60 * 5 - 25),
            ]),
            Conversation(person: nil, sender: "POST", lines: [
                Line(fromMe: false, text: "مرسوله شما امروز بین ساعت ۱۴ تا ۱۷ تحویل داده می‌شود. پیگیری: example.com/t/8K2Q", minutesAgo: 60 * 8),
            ]),
            Conversation(person: 3, sender: "", lines: [
                Line(fromMe: false, text: "می‌تونی اسلایدها رو قبل از جلسه بفرستی؟", minutesAgo: 60 * 26),
                Line(fromMe: true, text: "همین الان می‌فرستم", minutesAgo: 60 * 25),
                Line(fromMe: false, text: "رسید، ممنون!", minutesAgo: 60 * 25 - 30),
            ]),
            Conversation(person: 1, sender: "", lines: [
                Line(fromMe: false, text: "شنبه شب بازی داریم، میای؟", minutesAgo: 60 * 50),
                Line(fromMe: true, text: "حتماً. خوراکی با من", minutesAgo: 60 * 49),
            ]),
            Conversation(person: nil, sender: "SAFAR-ID", lines: [
                Line(fromMe: false, text: "کد ورود شما به حساب نمونه: 551203", minutesAgo: 60 * 75),
            ]),
            Conversation(person: 5, sender: "", lines: [
                Line(fromMe: true, text: "تولدت مبارک نگار جان! 🎂", minutesAgo: 60 * 24 * 4),
                Line(fromMe: false, text: "وای مرسی، خیلی لطف داری!! 😊", minutesAgo: 60 * 24 * 4 - 40),
            ]),
        ],
        calls: [
            Call(person: 2, number: "", type: "in", minutesAgo: 45, duration: 754, sim: 0),
            Call(person: 0, number: "", type: "out", minutesAgo: 130, duration: 212, sim: 0),
            Call(person: nil, number: "+98 21 5555 0100", type: "missed", minutesAgo: 60 * 6, duration: 0, sim: 1),
            Call(person: 3, number: "", type: "out", minutesAgo: 60 * 26, duration: 1260, sim: 1),
            Call(person: 4, number: "", type: "missed", minutesAgo: 60 * 30, duration: 0, sim: 0),
            Call(person: 1, number: "", type: "in", minutesAgo: 60 * 52, duration: 95, sim: 0),
            Call(person: nil, number: "+98 935 555 0177", type: "rejected", minutesAgo: 60 * 70, duration: 0, sim: 0),
            Call(person: 7, number: "", type: "out", minutesAgo: 60 * 96, duration: 180, sim: 1),
            Call(person: 5, number: "", type: "in", minutesAgo: 60 * 24 * 4, duration: 620, sim: 0),
        ],
        incomingOTP: ("BANK-NEMOONE","رمز پویای شما برای تایید پرداخت: ۷۳۹۲۰۴\nبانک نمونه"),
        replies: ["باشه 👍", "دارم میام!", "مرسی، بعداً حرف می‌زنیم", "عالیه"],
        ussdBalance: "اعتبار شما ۲۳۴٬۰۰۰ ریال است. حجم باقی‌مانده: ۴٫۲ گیگابایت تا ۱۰ دی.",
        ussdMenu: "۱. خرید بسته اینترنت\n۲. مانده دقایق مکالمه\n۳. رومینگ\n۰. خروج",
        ussdUnknown: "کد نامعتبر است. کد را بررسی کنید و دوباره تلاش کنید."
    )

    // MARK: - Arabic

    static let arabic = DemoContent(
        carriers: ("مدار", "نجم موبايل"),
        ownNumber: "+971 50 555 0134",
        people: [
            Person(name: "ليلى حسن", number: "+971 50 555 0182", label: "mobile", email: "layla@example.com", starred: true),
            Person(name: "يوسف العلي", number: "+971 55 555 0147", label: "mobile", email: nil, starred: false),
            Person(name: "أمي", number: "+971 4 555 0110", label: "home", email: nil, starred: true),
            Person(name: "سارة المنصوري", number: "+971 52 555 0199", label: "work", email: "sara@example.org", starred: false),
            Person(name: "خالد إبراهيم", number: "+971 56 555 0123", label: "mobile", email: nil, starred: false),
            Person(name: "نور الهدى", number: "+971 50 555 0165", label: "mobile", email: "noor@example.net", starred: true),
            Person(name: "عمر السيد", number: "+971 55 555 0171", label: "mobile", email: nil, starred: false),
            Person(name: "د. منى سالم", number: "+971 4 555 0190", label: "work", email: nil, starred: false),
            Person(name: "ريم عادل", number: "+971 52 555 0156", label: "mobile", email: nil, starred: false),
            Person(name: "طارق حداد", number: "+971 56 555 0138", label: "mobile", email: nil, starred: false),
        ],
        conversations: [
            Conversation(person: 0, sender: "", lines: [
                Line(fromMe: false, text: "هل ما زلنا على موعد العشاء الليلة؟", minutesAgo: 95),
                Line(fromMe: true, text: "نعم! الساعة 7:30 في المكان المعتاد؟", minutesAgo: 92),
                Line(fromMe: false, text: "ممتاز. سأحجز طاولة لثلاثة، خالد سينضم إلينا 🎉", minutesAgo: 90),
                Line(fromMe: true, text: "رائع، أراك هناك", minutesAgo: 12),
            ]),
            Conversation(person: nil, sender: "BANK", lines: [
                Line(fromMe: false, text: "رمز التحقق الخاص بك هو 482913. صالح لمدة 10 دقائق. لا تشاركه مع أي شخص.", minutesAgo: 3),
            ]),
            Conversation(person: 2, sender: "", lines: [
                Line(fromMe: false, text: "هل وصلت بالسلامة؟", minutesAgo: 60 * 5),
                Line(fromMe: true, text: "وصلت للتو. سأتصل بك بعد قليل ❤️", minutesAgo: 60 * 5 - 20),
                Line(fromMe: false, text: "اتصل بي عندما تصل إلى البيت", minutesAgo: 60 * 5 - 25),
            ]),
            Conversation(person: nil, sender: "PARCEL", lines: [
                Line(fromMe: false, text: "شحنتك في الطريق وستصل اليوم بين الساعة 2 و5 مساءً. التتبع: example.com/t/8K2Q", minutesAgo: 60 * 8),
            ]),
            Conversation(person: 3, sender: "", lines: [
                Line(fromMe: false, text: "هل يمكنك إرسال العرض قبل الاجتماع؟", minutesAgo: 60 * 26),
                Line(fromMe: true, text: "أرسله الآن", minutesAgo: 60 * 25),
                Line(fromMe: false, text: "وصلني، شكرًا!", minutesAgo: 60 * 25 - 30),
            ]),
            Conversation(person: 1, sender: "", lines: [
                Line(fromMe: false, text: "سهرة ألعاب يوم السبت؟", minutesAgo: 60 * 50),
                Line(fromMe: true, text: "أكيد. سأحضر الوجبات الخفيفة", minutesAgo: 60 * 49),
            ]),
            Conversation(person: nil, sender: "ACME-ID", lines: [
                Line(fromMe: false, text: "G-551203 هو رمز تسجيل الدخول الخاص بك.", minutesAgo: 60 * 75),
            ]),
            Conversation(person: 5, sender: "", lines: [
                Line(fromMe: true, text: "عيد ميلاد سعيد يا نور! 🎂", minutesAgo: 60 * 24 * 4),
                Line(fromMe: false, text: "شكرًا جزيلًا!! 😊", minutesAgo: 60 * 24 * 4 - 40),
            ]),
        ],
        calls: [
            Call(person: 2, number: "", type: "in", minutesAgo: 45, duration: 754, sim: 0),
            Call(person: 0, number: "", type: "out", minutesAgo: 130, duration: 212, sim: 0),
            Call(person: nil, number: "+971 800 555 0100", type: "missed", minutesAgo: 60 * 6, duration: 0, sim: 1),
            Call(person: 3, number: "", type: "out", minutesAgo: 60 * 26, duration: 1260, sim: 1),
            Call(person: 4, number: "", type: "missed", minutesAgo: 60 * 30, duration: 0, sim: 0),
            Call(person: 1, number: "", type: "in", minutesAgo: 60 * 52, duration: 95, sim: 0),
            Call(person: nil, number: "+971 55 555 0177", type: "rejected", minutesAgo: 60 * 70, duration: 0, sim: 0),
            Call(person: 7, number: "", type: "out", minutesAgo: 60 * 96, duration: 180, sim: 1),
            Call(person: 5, number: "", type: "in", minutesAgo: 60 * 24 * 4, duration: 620, sim: 0),
        ],
        incomingOTP: ("BANK", "رمزك لمرة واحدة لتأكيد الدفع هو 739204. لا تشاركه مع أحد."),
        replies: ["تمام 👍", "في الطريق!", "شكرًا، نتحدث لاحقًا", "ممتاز"],
        ussdBalance: "رصيدك 23.40 درهم. البيانات المتبقية: 4.2 جيجابايت حتى 31 ديسمبر.",
        ussdMenu: "1. شراء باقة بيانات\n2. الدقائق المتبقية\n3. التجوال\n0. خروج",
        ussdUnknown: "رمز غير معروف. تحقق من الرمز وحاول مرة أخرى."
    )
}
