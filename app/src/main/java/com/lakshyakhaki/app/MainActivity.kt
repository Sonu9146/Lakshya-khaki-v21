package com.lakshyakhaki.app

import androidx.compose.foundation.shape.RoundedCornerShape
import android.Manifest
import android.app.*
import android.app.TimePickerDialog
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.lakshyakhaki.app.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

private val Context.lakshyaDataStore by preferencesDataStore("lakshya_settings")
private val DAILY_MCQ_GOAL = intPreferencesKey("daily_mcq_goal")
private val DAILY_GROUND_GOAL = booleanPreferencesKey("daily_ground_goal")
private val REMINDER_ENABLED = booleanPreferencesKey("reminder_enabled")
private val REMINDER_HOUR = intPreferencesKey("reminder_hour")
private val REMINDER_MINUTE = intPreferencesKey("reminder_minute")
private val MISTAKE_IDS = stringSetPreferencesKey("mistake_ids")
private val LEADERBOARD_POINTS = intPreferencesKey("leaderboard_points")
private val DAILY_XP_GOAL = intPreferencesKey("daily_xp_goal")
private val PLANNER_DAYS = stringSetPreferencesKey("planner_days")
private val LEADERBOARD_NAME = stringPreferencesKey("leaderboard_name")

private const val REMINDER_CHANNEL = "lakshya_reminders"
private const val REMINDER_REQUEST = 4101

private val db by lazy { AppDatabase.get(App.instance) }

class App : android.app.Application() {
    companion object { lateinit var instance: App }
    override fun onCreate() { super.onCreate(); instance = this }
}

private fun scheduleDailyReminder(context: Context, hour: Int, minute: Int) {
    val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val intent = Intent(context, ReminderReceiver::class.java)
    val pending = PendingIntent.getBroadcast(
        context, REMINDER_REQUEST, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
    }
    alarm.setInexactRepeating(AlarmManager.RTC_WAKEUP, cal.timeInMillis, AlarmManager.INTERVAL_DAY, pending)
}

private fun cancelDailyReminder(context: Context) {
    val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val intent = Intent(context, ReminderReceiver::class.java)
    val pending = PendingIntent.getBroadcast(
        context, REMINDER_REQUEST, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    alarm.cancel(pending)
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(
                REMINDER_CHANNEL, "Lakshya Khaki Reminders", NotificationManager.IMPORTANCE_DEFAULT
            ))
        }
        val n = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(context, REMINDER_CHANNEL)
        else Notification.Builder(context)
        manager.notify(
            REMINDER_REQUEST,
            n.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("लक्ष्य खाकी")
                .setContentText("आजची MCQ practice आणि Ground training check करा.")
                .setAutoCancel(true).build()
        )
    }
}

data class StudyTopic(val title: String, val subtitle: String, val bullets: List<String>)
data class Question(val category: String, val question: String, val options: List<String>, val answer: Int, val topic: String = category, val id: String = question.hashCode().toString(), val explanation: String = "योग्य उत्तर: ${options[answer]}")

private val studyTopics = listOf(
    StudyTopic("मराठी व्याकरण","संधी • समास • शब्दशक्ती • वाक्यरचना",
        listOf("संधीचे प्रकार आणि उदाहरणे","समासाचे प्रमुख प्रकार","शब्दांचे अर्थ व विरुद्धार्थी","वाक्यशुद्धी सराव")),
    StudyTopic("गणित","टक्केवारी • सरासरी • गुणोत्तर • संख्या",
        listOf("टक्केवारीची मूलभूत सूत्रे","सरासरी व गुणोत्तर","नफा-तोटा व साधे व्याज","संख्या मालिका सराव")),
    StudyTopic("GK / GS","महाराष्ट्र • भारत • सामान्य विज्ञान",
        listOf("महाराष्ट्र सामान्यज्ञान","भारतीय राज्यघटना basics","दैनंदिन विज्ञान","महत्त्वाचे दिवस व तथ्ये")),
    StudyTopic("Reasoning","Series • Coding • Ranking • Analogy",
        listOf("अक्षर व संख्या मालिका","Coding-Decoding","क्रमांक व दिशा","साम्य व वर्गीकरण"))
)

private val questions = listOf(
    // Marathi
    Question("मराठी","‘रामाने आंबा खाल्ला.’ या वाक्यातील कर्ता कोणता?",listOf("रामाने","आंबा","खाल्ला","या"),0,"वाक्यरचना"),
    Question("मराठी","‘सुंदर’ या शब्दाचा विरुद्धार्थी शब्द कोणता?",listOf("कुरूप","मोठा","गोड","लहान"),0,"शब्दसंपदा"),
    Question("मराठी","‘राजपुत्र’ हा कोणत्या समासाचा प्रकार आहे?",listOf("द्वंद्व","तत्पुरुष","बहुव्रीही","अव्ययीभाव"),1,"समास"),
    Question("मराठी","‘मी शाळेत जातो.’ या वाक्यातील क्रियापद कोणते?",listOf("मी","शाळेत","जातो","या"),2,"वाक्यरचना"),
    Question("मराठी","‘देव + आलय’ यांचा संधीयोग कोणता?",listOf("देवालय","देवाआलय","देवालयी","देवाल्य"),0,"संधी"),
    Question("मराठी","‘आई’ या शब्दाचे अनेकवचन कोणते?",listOf("आईं","आईया","आया","आई"),2,"शब्दसंपदा"),
    Question("मराठी","‘हात धुऊन मागे लागणे’ या वाक्प्रचाराचा अर्थ काय?",listOf("मदत करणे","पाठलाग करणे","हात धुणे","काम थांबवणे"),1,"वाक्प्रचार"),
    Question("मराठी","‘जो अभ्यास करतो तो यशस्वी होतो.’ हे कोणत्या प्रकारचे वाक्य आहे?",listOf("केवल","संयुक्त","मिश्र","उद्गारार्थी"),2,"वाक्यरचना"),

    // Maths
    Question("गणित","25% of 240 किती?",listOf("40","50","60","80"),2,"टक्केवारी"),
    Question("गणित","15 × 8 − 20 = ?",listOf("90","100","110","120"),1,"मूलभूत गणित"),
    Question("गणित","एका संख्येचा 3/5 भाग 36 आहे. संख्या किती?",listOf("48","60","72","90"),1,"अपूर्णांक"),
    Question("गणित","2, 6, 12, 20, 30, ? पुढील संख्या कोणती?",listOf("40","42","44","46"),1,"संख्या मालिका"),
    Question("गणित","एका वस्तूची किंमत ₹500 आहे. 10% सवलतीनंतर किंमत किती?",listOf("₹440","₹450","₹460","₹475"),1,"टक्केवारी"),
    Question("गणित","12 आणि 18 यांचा म.सा.वि. किती?",listOf("3","6","9","12"),1,"संख्या"),
    Question("गणित","4 कामगार एखादे काम 12 दिवसांत करतात. समान वेगाने 8 कामगार किती दिवस घेतील?",listOf("3","6","8","10"),1,"काम व वेळ"),
    Question("गणित","60 किमी/तास वेगाने 2 तासांत किती अंतर पार होईल?",listOf("100 किमी","120 किमी","140 किमी","160 किमी"),1,"वेग-अंतर-वेळ"),

    // GK/GS
    Question("GK/GS","महाराष्ट्राची राजधानी कोणती?",listOf("मुंबई","पुणे","नागपूर","नाशिक"),0,"महाराष्ट्र GK"),
    Question("GK/GS","भारताचा राष्ट्रीय प्राणी कोणता?",listOf("सिंह","वाघ","हत्ती","मोर"),1,"भारत GK"),
    Question("GK/GS","पाण्याचे रासायनिक सूत्र कोणते?",listOf("CO₂","O₂","H₂O","NaCl"),2,"सामान्य विज्ञान"),
    Question("GK/GS","भारतीय संविधानाचा स्वीकार कोणत्या दिवशी झाला?",listOf("15 ऑगस्ट 1947","26 नोव्हेंबर 1949","26 जानेवारी 1950","2 ऑक्टोबर 1950"),1,"भारतीय राज्यघटना"),
    Question("GK/GS","महाराष्ट्र दिन कोणत्या दिवशी साजरा केला जातो?",listOf("1 मे","15 ऑगस्ट","26 जानेवारी","2 ऑक्टोबर"),0,"महाराष्ट्र GK"),
    Question("GK/GS","सूर्याच्या सर्वात जवळचा ग्रह कोणता?",listOf("शुक्र","पृथ्वी","बुध","मंगळ"),2,"सामान्य विज्ञान"),
    Question("GK/GS","भारतीय संविधानाचे शिल्पकार म्हणून कोणाला ओळखले जाते?",listOf("महात्मा गांधी","डॉ. बाबासाहेब आंबेडकर","लोकमान्य टिळक","सुभाषचंद्र बोस"),1,"भारतीय राज्यघटना"),
    Question("GK/GS","रक्ताचा लाल रंग कोणत्या घटकामुळे असतो?",listOf("कॅल्शियम","हिमोग्लोबिन","प्लाझ्मा","इन्सुलिन"),1,"सामान्य विज्ञान"),

    // Reasoning
    Question("Reasoning","A, C, E, G, ? पुढील अक्षर कोणते?",listOf("H","I","J","K"),1,"अक्षर मालिका"),
    Question("Reasoning","3, 9, 27, 81, ? पुढील संख्या कोणती?",listOf("162","216","243","324"),2,"संख्या मालिका"),
    Question("Reasoning","जर CAT = DBU असेल, तर DOG = ?",listOf("EPH","EOG","DPH","FPI"),0,"Coding-Decoding"),
    Question("Reasoning","एका रांगेत राहुल डावीकडून 8वा आणि उजवीकडून 13वा आहे. एकूण किती जण?",listOf("19","20","21","22"),1,"क्रमांक"),
    Question("Reasoning","5, 10, 20, 40, ? पुढील संख्या कोणती?",listOf("60","70","80","90"),2,"संख्या मालिका"),
    Question("Reasoning","जर NORTH ला OQSUJ असे कोड केले, तर EAST चे योग्य कोड कोणते?",listOf("FBTU","FZTU","DBRS","GAST"),0,"Coding-Decoding"),
    Question("Reasoning","मोहन हा सोहनपेक्षा उंच आहे आणि सोहन हा रोहनपेक्षा उंच आहे. सर्वांत ठेंगणा कोण?",listOf("मोहन","सोहन","रोहन","माहिती अपुरी"),2,"तुलना"),
    Question("Reasoning","घड्याळात 3 वाजता मिनिट काटा कोणत्या अंकावर असतो?",listOf("3","6","9","12"),3,"दिशा व घड्याळ"),

    // Mixed extra practice
    Question("मराठी","‘जलद’ या शब्दाचा समानार्थी शब्द कोणता?",listOf("वेगवान","मंद","जड","शांत"),0,"शब्दसंपदा"),
    Question("मराठी","‘नीलकमल’ हा कोणत्या समासाचा प्रकार आहे?",listOf("कर्मधारय","द्वंद्व","बहुव्रीही","अव्ययीभाव"),0,"समास"),
    Question("गणित","200 च्या 15% किती?",listOf("20","25","30","35"),2,"टक्केवारी"),
    Question("गणित","9² + 4² = ?",listOf("81","97","105","117"),1,"मूलभूत गणित"),
    Question("GK/GS","पृथ्वीचा नैसर्गिक उपग्रह कोणता?",listOf("सूर्य","चंद्र","मंगळ","शुक्र"),1,"सामान्य विज्ञान"),
    Question("GK/GS","भारताचे राष्ट्रगीत कोणी लिहिले?",listOf("रवींद्रनाथ टागोर","बंकिमचंद्र चट्टोपाध्याय","कुसुमाग्रज","विनोबा भावे"),0,"भारत GK"),
    Question("Reasoning","B, D, F, H, ? पुढील अक्षर कोणते?",listOf("I","J","K","L"),1,"अक्षर मालिका"),
    Question("Reasoning","1, 4, 9, 16, ? पुढील संख्या कोणती?",listOf("20","24","25","27"),2,"संख्या मालिका")
)

data class MockTest(val title: String, val subtitle: String, val category: String, val count: Int)

private val mockTests = listOf(
    MockTest("Marathi Grammar Mock", "व्याकरणावर timed test", "मराठी", 8),
    MockTest("Maths Mock", "गणिताचा जलद सराव", "गणित", 8),
    MockTest("GK / GS Mock", "सामान्यज्ञान + विज्ञान", "GK/GS", 8),
    MockTest("Reasoning Mock", "तर्कशक्तीचा सराव", "Reasoning", 8),
    MockTest("Full Syllabus Mock", "सर्व विषयांचा mixed test", "All", 32)
)

private val topicMocks = listOf(
    MockTest("संधी + समास", "मराठी topic practice", "TOPIC::संधी", 4),
    MockTest("शब्दसंपदा", "समानार्थी + विरुद्धार्थी", "TOPIC::शब्दसंपदा", 4),
    MockTest("टक्केवारी", "Percentage practice", "TOPIC::टक्केवारी", 4),
    MockTest("संख्या मालिका", "Number series practice", "TOPIC::संख्या मालिका", 4),
    MockTest("सामान्य विज्ञान", "Science quick test", "TOPIC::सामान्य विज्ञान", 4),
    MockTest("भारतीय राज्यघटना", "Constitution practice", "TOPIC::भारतीय राज्यघटना", 4),
    MockTest("Coding-Decoding", "Coding practice", "TOPIC::Coding-Decoding", 4),
    MockTest("क्रमांक + तुलना", "Ranking practice", "TOPIC::क्रमांक", 4)
)

private fun parseTimeSeconds(v: String): Double? {
    val t=v.trim()
    if (t.contains(":")) {
        val p=t.split(":"); if (p.size==2) return (p[0].toDoubleOrNull() ?: return null)*60+(p[1].toDoubleOrNull() ?: return null)
    }
    return t.toDoubleOrNull()
}
private fun formatTime(s: Double) = if (s >= 60) "${(s/60).toInt()}:${String.format(Locale.getDefault(),"%04.1f",s%60)}"
else String.format(Locale.getDefault(),"%.1f sec",s)

private fun dateKey(ts: Long) = SimpleDateFormat("yyyy-MM-dd",Locale.getDefault()).format(Date(ts))
private fun todayCount(ts: List<Long>) = ts.count { dateKey(it) == dateKey(System.currentTimeMillis()) }

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent {
            LakshyaKhakiApp(
                requestNotificationPermission = {
                    if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            )
        }
    }
}

@Composable
fun LakshyaKhakiApp(requestNotificationPermission: () -> Unit) {
    val nav=rememberNavController()
    val colors = darkColorScheme(
        primary=Color(0xFF5EA7FF),
        secondary=Color(0xFF8CBFFF),
        background=Color(0xFF07111F),
        surface=Color(0xFF0D1A2B),
        surfaceVariant=Color(0xFF15253A)
    )
    MaterialTheme(colorScheme=colors) {
        Scaffold(
            containerColor=colors.background,
            bottomBar={
                NavigationBar(containerColor=Color(0xFF0A1626)) {
                    listOf("home" to "Home","study" to "Study","ground" to "Ground","progress" to "Progress","profile" to "Profile").forEach { (route,label) ->
                        val current=nav.currentBackStackEntryAsState().value?.destination?.route
                        NavigationBarItem(
                            selected=current==route,
                            onClick={ nav.navigate(route) { launchSingleTop=true; popUpTo("home") { saveState=true } } },
                            icon={Text(if(route=="home") "⌂" else if(route=="study") "📚" else if(route=="ground") "🏃" else if(route=="progress") "📈" else "👤")},
                            label={Text(label)}
                        )
                    }
                }
            }
        ) { p ->
            NavHost(nav,"home",Modifier.padding(p)) {
                composable("home"){Home(nav)}
                composable("study"){Study(onQuiz={cat->nav.navigate("quiz/${cat.replace("/","_")}")}, onMocks={nav.navigate("mocks")})}
                composable("mocks"){MockTests{cat->nav.navigate("quiz/${cat.replace("/","_")}")}}
                composable("quiz/{category}") { back ->
                    val cat=back.arguments?.getString("category")?.replace("_","/") ?: "All"
                    Quiz(cat){nav.popBackStack()}
                }
                composable("ground"){Ground()}
                composable("progress"){Progress()}
                composable("profile"){Profile(nav,requestNotificationPermission)}
                composable("daily"){DailyGoals()}
                composable("mistakes"){MistakeBook(nav)}
                composable("smart"){SmartPractice(nav)}
                composable("rank"){RankSystem(nav)}
                composable("analytics"){AnalyticsDashboard(nav)}
                composable("missions"){DailyMissions(nav)}
                composable("planner"){StudyPlanner(nav)}
                composable("reminders"){ReminderSettings(requestNotificationPermission)}
            }
        }
    }
}


@Composable
fun Home(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var todayQuiz by remember { mutableStateOf(0) }
    var todayGround by remember { mutableStateOf(0) }
    var xp by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        todayQuiz = db.quizDao().all().first()
            .count { System.currentTimeMillis() - it.timestamp < 24L * 60 * 60 * 1000 }
        todayGround = db.groundDao().all().first()
            .count { System.currentTimeMillis() - it.timestamp < 24L * 60 * 60 * 1000 }
        xp = context.lakshyaDataStore.data.first()[LEADERBOARD_POINTS] ?: 0
    }

    val todayProgress = ((todayQuiz * 10 + todayGround * 15) / 50f).coerceIn(0f, 1f)

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(
                    Modifier.padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("लक्ष्य खाकी", style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.ExtraBold)
                    Text("तुमचे लक्ष्य, आमचे मार्गदर्शन.",
                        style = MaterialTheme.typography.titleMedium)
                    Text("आजची तयारी सुरू ठेवा 💪",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("🔥 आजची Progress", fontWeight = FontWeight.Bold)
                        Text("${(todayProgress * 100).toInt()}%")
                    }
                    LinearProgressIndicator(
                        progress = { todayProgress },
                        Modifier.fillMaxWidth()
                    )
                    Text("$todayQuiz quiz • $todayGround ground attempts • $xp XP total",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Card(Modifier.weight(1f), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("📝 Quiz")
                        Text("$todayQuiz", style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold)
                        Text("आज")
                    }
                }
                Card(Modifier.weight(1f), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("🏃 Ground")
                        Text("$todayGround", style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold)
                        Text("आज")
                    }
                }
            }
        }

        item {
            Text("⚡ Quick Actions", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button({ nav.navigate("mocks") }, Modifier.weight(1f)) {
                    Text("🎯 Mock Test")
                }
                Button({ nav.navigate("missions") }, Modifier.weight(1f)) {
                    Text("🔥 Missions")
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton({ nav.navigate("planner") }, Modifier.fillMaxWidth()) {
                    Text("📅 Study Planner")
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton({ nav.navigate("analytics") }, Modifier.weight(1f)) {
                    Text("📊 Analytics")
                }
                OutlinedButton({ nav.navigate("rank") }, Modifier.weight(1f)) {
                    Text("🏆 Rank")
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("💡 आजचा Focus", fontWeight = FontWeight.Bold)
                    Text("एक mock test + एक ground attempt पूर्ण करण्याचा प्रयत्न करा.")
                }
            }
        }
    }
}

@Composable
fun Study(onQuiz:(String)->Unit, onMocks:()->Unit) {
    var selected by remember{mutableStateOf<StudyTopic?>(null)}
    if(selected==null) Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("अभ्यास",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
        Text("रोज थोडं, सातत्याने मोठी तयारी.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onMocks,Modifier.fillMaxWidth()){Text("📝 Mock Tests")}
        studyTopics.forEach{topic->Card(Modifier.fillMaxWidth().clickable{selected=topic}){
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){Text(topic.title,style=MaterialTheme.typography.titleLarge);Text(topic.subtitle,color=MaterialTheme.colorScheme.onSurfaceVariant);Text("${topic.bullets.size} मुख्य topics",style=MaterialTheme.typography.labelMedium)}
        }}
    } else {
        val topic=selected!!
        Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            TextButton({selected=null}){Text("← अभ्यासाकडे")}
            Text(topic.title,style=MaterialTheme.typography.headlineSmall)
            topic.bullets.forEachIndexed{i,b->Card(Modifier.fillMaxWidth()){Row(Modifier.padding(15.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){Text("${i+1}.");Text(b)}}}
            Button({onQuiz(topic.title)},Modifier.fillMaxWidth()){Text("या विषयाचा MCQ सराव")}
        }
    }
}

@Composable
fun MockTests(onStart:(String)->Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Mock Tests",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
        Text("30 sec/question • Full syllabus + subject + topic practice.",color=MaterialTheme.colorScheme.onSurfaceVariant)

        Text("Subject Mocks",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
        mockTests.forEach { test ->
            Card(Modifier.fillMaxWidth().clickable{onStart(test.category)}) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text(test.title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                    Text(test.subtitle,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${test.count} questions • 30 sec/question",style=MaterialTheme.typography.labelMedium)
                }
            }
        }

        Text("Topic-wise Practice",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
        topicMocks.forEach { test ->
            Card(Modifier.fillMaxWidth().clickable{onStart(test.category)}) {
                Row(Modifier.padding(15.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("🎯",style=MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(test.title,fontWeight=FontWeight.Bold)
                        Text(test.subtitle,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun Quiz(category:String,onDone:()->Unit) {
    val context=LocalContext.current
    val filterParts = remember(category) { category.split("::", limit = 2) }
    val filtered=remember(category){
        when {
            category=="All" -> questions
            filterParts.firstOrNull()=="TOPIC" -> questions.filter{it.topic==filterParts.getOrElse(1){""}}.ifEmpty{questions}
            else -> questions.filter{it.category==category}.ifEmpty{questions}
        }
    }
    var index by remember{mutableStateOf(0)}
    var answers by remember{mutableStateOf(List(filtered.size){-1})}
    var seconds by remember{mutableStateOf(filtered.size*30)}
    var finished by remember{mutableStateOf(false)}
    LaunchedEffect(finished){while(!finished&&seconds>0){delay(1000);seconds--};if(!finished&&seconds==0)finished=true}
    if(finished){
        val score=answers.indices.count{answers[it]==filtered[it].answer}
        val attempted=answers.count{it>=0}
        LaunchedEffect(Unit){
            db.quizDao().insert(QuizResult(category = category, score = score, total = filtered.size))
            awardXp(context, if (score == filtered.size) 20 else 10)
            val wrongIds = answers.indices.filter{answers[it] != filtered[it].answer}.map{filtered[it].id}.toSet()
            context.lakshyaDataStore.edit { prefs ->
                val old = prefs[MISTAKE_IDS] ?: emptySet()
                prefs[MISTAKE_IDS] = (old + wrongIds).take(100).toSet()
            }
        }
        Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
            Text("Quiz Result",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
            Text("$score / ${filtered.size}",style=MaterialTheme.typography.displaySmall)
            Text("${if(filtered.isEmpty())0 else score*100/filtered.size}% • Attempted $attempted/${filtered.size}")
            Button({onDone()},Modifier.fillMaxWidth()){Text("Back")}
        }
        return
    }
    val q=filtered[index]
    Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Question ${index+1}/${filtered.size}");Text(String.format(Locale.getDefault(),"%02d:%02d",seconds/60,seconds%60))}
        LinearProgressIndicator(progress={(index+1f)/filtered.size},modifier=Modifier.fillMaxWidth())
        Card(Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
            Text(q.category,style=MaterialTheme.typography.labelLarge)
            Text(q.question,style=MaterialTheme.typography.titleLarge)
            q.options.forEachIndexed{i,opt->OutlinedButton({answers=answers.toMutableList().also{it[index]=i}},Modifier.fillMaxWidth()){Text("${('A'.code+i).toChar()}. $opt"+if(answers[index]==i)" ✓" else "")}}
        }}
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedButton({if(index>0)index--},enabled=index>0,modifier=Modifier.weight(1f)){Text("Previous")}
            Button({if(index<filtered.lastIndex)index++ else finished=true},Modifier.weight(1f)){Text(if(index==filtered.lastIndex)"Finish" else "Next")}
        }
    }
}

@Composable
fun Ground() {
    val scope=rememberCoroutineScope()
    var event by remember{mutableStateOf("1600m")}
    var value by remember{mutableStateOf("")}
    var history by remember{mutableStateOf(listOf<GroundResult>())}
    LaunchedEffect(Unit){history=db.groundDao().all().first()}
    val rows=history.filter{it.event==event}
    val best=if(event=="Shot Put") rows.mapNotNull{it.value.toDoubleOrNull()}.maxOrNull()?.let{"%.2f m".format(it)}
    else rows.mapNotNull{parseTimeSeconds(it.value)}.minOrNull()?.let(::formatTime)
    Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("Ground Tracker",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(7.dp)){listOf("1600m","100m","Shot Put").forEach{e->FilterChip(event==e,{event=e;value=""},{Text(e)})}}
        OutlinedTextField(value,{value=it.filter{c->c.isDigit()||c=='.'||c==':'}} ,label={Text(if(event=="Shot Put")"Distance (m)" else "Time (sec)")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        Button({if(value.isNotBlank())scope.launch{db.groundDao().insert(GroundResult(event=event,value=value));value="";history=db.groundDao().all().first()}},enabled=value.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text("Save Performance")}
        Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){Text("Personal Best",style=MaterialTheme.typography.titleMedium);Text(best?:"No record yet",style=MaterialTheme.typography.headlineSmall);Text("${rows.size} saved attempt(s)",color=MaterialTheme.colorScheme.onSurfaceVariant)}}
        Text("Recent History",style=MaterialTheme.typography.titleLarge)
        LazyColumn{items(rows.take(8)){r->ListItem({Text(r.value+(if(event=="Shot Put")" m" else " sec"))},{Text(SimpleDateFormat("dd MMM, HH:mm",Locale.getDefault()).format(Date(r.timestamp)))})}}
    }
}

@Composable
fun Progress() {
    val ground=remember{mutableStateListOf<GroundResult>()}
    val quizzes=remember{mutableStateListOf<QuizResult>()}
    LaunchedEffect(Unit){ground.addAll(db.groundDao().all().first());quizzes.addAll(db.quizDao().all().first())}
    Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("Progress",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
        listOf("1600m","100m","Shot Put").forEach{event->
            val rows=ground.filter{it.event==event};val vals=rows.mapNotNull{if(event=="Shot Put")it.value.toDoubleOrNull() else parseTimeSeconds(it.value)}
            val best=if(event=="Shot Put")vals.maxOrNull() else vals.minOrNull()
            Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
                Text(event,style=MaterialTheme.typography.titleLarge);Text("Personal Best: "+(if(best==null)"—" else if(event=="Shot Put")"%.2f m".format(best) else formatTime(best)));Text("${rows.size} attempts",color=MaterialTheme.colorScheme.onSurfaceVariant)
            }}
        }
        val total=quizzes.sumOf{it.total};val score=quizzes.sumOf{it.score};val pct=if(total>0)score*100/total else 0
        Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){Text("Study Performance",style=MaterialTheme.typography.titleLarge);Text("${quizzes.size} quiz attempts");Text("Overall MCQ accuracy: $pct%");LinearProgressIndicator(progress={pct/100f},modifier=Modifier.fillMaxWidth())}}
    }
}

private fun calculateStreak(ts:List<Long>):Int{
    val set=ts.map(::dateKey).toSet();var d=Calendar.getInstance();var streak=0
    while(set.contains(SimpleDateFormat("yyyy-MM-dd",Locale.getDefault()).format(d.time))){streak++;d.add(Calendar.DAY_OF_YEAR,-1)}
    return streak
}

@Composable
fun Profile(nav:NavHostController,requestPermission:()->Unit){
    val scope=rememberCoroutineScope();val context=LocalContext.current
    var ground by remember{mutableStateOf(listOf<GroundResult>())};var quizzes by remember{mutableStateOf(listOf<QuizResult>())}
    LaunchedEffect(Unit){ground=db.groundDao().all().first();quizzes=db.quizDao().all().first()}
    val streak=calculateStreak(ground.map{it.timestamp}+quizzes.map{it.timestamp})
    Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("Profile",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
        Card(Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp)){Text("Current Streak",style=MaterialTheme.typography.titleMedium);Text("$streak day 🔥",style=MaterialTheme.typography.headlineSmall);Text("Quiz: ${quizzes.size} • Ground: ${ground.size}",color=MaterialTheme.colorScheme.onSurfaceVariant)}}
        Button({nav.navigate("daily")},Modifier.fillMaxWidth()){Text("🎯 Daily Goals")}
        OutlinedButton({nav.navigate("smart")},Modifier.fillMaxWidth()){Text("🧠 Smart Practice")}
        OutlinedButton({nav.navigate("rank")},Modifier.fillMaxWidth()){Text("🏆 Rank & Points")}
        OutlinedButton({nav.navigate("analytics")},Modifier.fillMaxWidth()){Text("📊 Analytics Dashboard")}
        OutlinedButton({nav.navigate("missions")},Modifier.fillMaxWidth()){Text("🔥 Daily Missions")}
        OutlinedButton({nav.navigate("planner")},Modifier.fillMaxWidth()){Text("📅 Study Planner")}
        OutlinedButton({nav.navigate("mistakes")},Modifier.fillMaxWidth()){Text("📕 Mistake Book")}
        OutlinedButton({nav.navigate("reminders")},Modifier.fillMaxWidth()){Text("🔔 Reminder Settings")}
        Text("Achievements",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
        val bestQuiz=quizzes.maxOfOrNull{if(it.total>0)it.score*100/it.total else 0}?:0
        listOf(
            "🎯 First Step" to (quizzes.isNotEmpty()),"🧠 Quiz Starter" to (quizzes.size>=5),
            "🏃 Ground Ready" to (ground.isNotEmpty()),"🔥 Training Mode" to (ground.size>=10),
            "🏆 80% Club" to (bestQuiz>=80),"⚡ 3 Day Streak" to (streak>=3),"👑 7 Day Streak" to (streak>=7)
        ).forEach{(name,ok)->Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=if(ok)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)){Text(name+(if(ok)" ✓" else " 🔒"),Modifier.padding(14.dp))}}
    }
}


@Composable
fun QuestionReview(q: Question, selected: Int?) {
    val correct = selected == q.answer
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(q.topic, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
            Text(q.question, fontWeight = FontWeight.Bold)
            Text("तुमचे उत्तर: " + (selected?.let { q.options.getOrNull(it) } ?: "उत्तर दिले नाही"),
                color = if (correct) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error)
            Text("योग्य उत्तर: ${q.options[q.answer]}", fontWeight = FontWeight.SemiBold)
            Text(q.explanation)
        }
    }
}

@Composable
fun MistakeBook(nav: NavHostController) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var ids by remember{mutableStateOf(setOf<String>())}
    LaunchedEffect(Unit){
        ids=context.lakshyaDataStore.data.first()[MISTAKE_IDS] ?: emptySet()
    }
    val mistakes=questions.filter{ids.contains(it.id)}

    Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
            Text("Mistake Book",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
            Text("${mistakes.size}",style=MaterialTheme.typography.titleLarge)
        }
        Text("चुकीचे झालेले प्रश्न इथे save होतात. पुन्हा practice करा.",color=MaterialTheme.colorScheme.onSurfaceVariant)

        if(mistakes.isEmpty()){
            Card(Modifier.fillMaxWidth()){
                Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text("🎉 अजून mistakes नाहीत!",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                    Text("Mock Test सोडवा. चुकीचे प्रश्न आपोआप इथे येतील.")
                }
            }
        } else {
            LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp),modifier=Modifier.weight(1f)){
                items(mistakes){q->
                    Card(Modifier.fillMaxWidth()){
                        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                            Text("${q.category} • ${q.topic}",style=MaterialTheme.typography.labelLarge)
                            Text(q.question,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                            Text("योग्य उत्तर: ${q.options[q.answer]}")
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedButton({
                    scope.launch{
                        context.lakshyaDataStore.edit{it[MISTAKE_IDS]=emptySet()}
                        ids=emptySet()
                    }
                },Modifier.weight(1f)){Text("Clear Book")}
                Button({nav.navigate("quiz/All")},Modifier.weight(1f)){Text("Practice Again")}
            }
        }
    }
}




private suspend fun awardXp(context: android.content.Context, amount: Int) {
    context.lakshyaDataStore.edit { prefs ->
        prefs[LEADERBOARD_POINTS] = (prefs[LEADERBOARD_POINTS] ?: 0) + amount
    }
}




@Composable
fun StudyPlanner(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val days = listOf("सोम", "मंगळ", "बुध", "गुरु", "शुक्र", "शनि", "रवि")
    val defaultPlan = setOf("सोम", "मंगळ", "बुध", "गुरु", "शुक्र", "शनि")
    var selected by remember { mutableStateOf(defaultPlan) }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val prefs = context.lakshyaDataStore.data.first()
        selected = prefs[PLANNER_DAYS] ?: defaultPlan
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("📅 Study Planner",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold)
        Text("तुमच्या आठवड्याचा simple preparation plan सेट करा.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Weekly Practice Days",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold)

                days.forEach { day ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = day in selected,
                            onCheckedChange = {
                                selected = if (it) selected + day else selected - day
                                saved = false
                            }
                        )
                        Text(day)
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Suggested Routine", fontWeight = FontWeight.Bold)
                Text("सोम: Marathi + Maths")
                Text("मंगळ: Reasoning + 100m practice")
                Text("बुध: GK/GS + revision")
                Text("गुरु: Marathi + Mock Test")
                Text("शुक्र: Maths + Reasoning")
                Text("शनि: Full Mock + Ground tracking")
                Text("रवि: Light revision / recovery")
            }
        }

        Button(
            onClick = {
                scope.launch {
                    context.lakshyaDataStore.edit { it[PLANNER_DAYS] = selected }
                    saved = true
                }
            },
            Modifier.fillMaxWidth()
        ) {
            Text(if (saved) "✅ Plan Saved" else "Save Weekly Plan")
        }

        OutlinedButton(
            onClick = { nav.navigate("missions") },
            Modifier.fillMaxWidth()
        ) { Text("🔥 Open Daily Missions") }
    }
}

@Composable
fun DailyMissions(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var points by remember { mutableStateOf(0) }
    var xpGoal by remember { mutableStateOf(50) }
    var mcqDone by remember { mutableStateOf(0) }
    var groundDone by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val p = context.lakshyaDataStore.data.first()
        points = p[LEADERBOARD_POINTS] ?: 0
        xpGoal = p[DAILY_XP_GOAL] ?: 50
        mcqDone = db.quizDao().all().first()
            .count { System.currentTimeMillis() - it.timestamp < 24L * 60 * 60 * 1000 }
        groundDone = db.groundDao().all().first()
            .any { System.currentTimeMillis() - it.timestamp < 24L * 60 * 60 * 1000 }
    }

    val earnedToday = (mcqDone * 10) + if (groundDone) 15 else 0
    val progress = (earnedToday.toFloat() / xpGoal).coerceIn(0f, 1f)
    val complete = earnedToday >= xpGoal

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("🔥 Daily Missions",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold)
        Text("आजची छोटी targets पूर्ण करा आणि XP वाढवा.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (complete) "🏆 Daily Mission Complete!" else "Today's XP")
                Text("$earnedToday / $xpGoal XP",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold)
                LinearProgressIndicator(
                    progress = { progress },
                    Modifier.fillMaxWidth()
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Today's Checklist",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold)

                Text("${if (mcqDone > 0) "✅" else "⬜"} Quiz practice — +10 XP each")
                Text("${if (groundDone) "✅" else "⬜"} Ground attempt — +15 XP")
                Text("${if (complete) "✅" else "⬜"} Daily XP target — $xpGoal XP")
            }
        }

        Text("🎯 Set Daily XP Target", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold)

        Slider(
            value = xpGoal.toFloat(),
            onValueChange = { xpGoal = it.toInt() },
            valueRange = 20f..150f,
            steps = 12
        )
        Text("$xpGoal XP")

        Button(
            onClick = {
                scope.launch {
                    context.lakshyaDataStore.edit { it[DAILY_XP_GOAL] = xpGoal }
                }
            },
            Modifier.fillMaxWidth()
        ) {
            Text("Save Daily Target")
        }

        OutlinedButton(
            onClick = { nav.navigate("analytics") },
            Modifier.fillMaxWidth()
        ) { Text("📊 View Analytics") }
    }
}

@Composable
fun AnalyticsDashboard(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var quizResults by remember { mutableStateOf<List<QuizResult>>(emptyList()) }
    var groundResults by remember { mutableStateOf<List<GroundResult>>(emptyList()) }

    LaunchedEffect(Unit) {
        quizResults = db.quizDao().all().first()
        groundResults = db.groundDao().all().first()
    }

    val quizTotal = quizResults.sumOf { it.total }
    val quizScore = quizResults.sumOf { it.score }
    val accuracy = if (quizTotal == 0) 0 else (quizScore * 100 / quizTotal)
    val best1600 = groundResults.filter { it.event == "1600m" }.mapNotNull { parseTimeSeconds(it.value) }.minOrNull()
    val best100 = groundResults.filter { it.event == "100m" }.mapNotNull { parseTimeSeconds(it.value) }.minOrNull()
    val bestShot = groundResults.filter { it.event == "Shot Put" }.mapNotNull { it.value.toDoubleOrNull() }.maxOrNull()

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("📊 Analytics Dashboard",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold)
        Text("तुमची study + ground performance एकाच ठिकाणी.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Card(Modifier.weight(1f)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Quiz Attempts", style = MaterialTheme.typography.labelMedium)
                    Text("${quizResults.size}", style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold)
                }
            }
            Card(Modifier.weight(1f)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Accuracy", style = MaterialTheme.typography.labelMedium)
                    Text("$accuracy%", style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold)
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🎯 Ground Personal Bests",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold)
                Text("1600m: ${best1600?.let { formatTime(it) } ?: "—"}")
                Text("100m: ${best100?.let { formatTime(it) } ?: "—"}")
                Text("Shot Put: ${bestShot?.let { String.format("%.2f", it) } ?: "—"}")
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("📅 Recent Activity",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold)
                if (quizResults.isEmpty() && groundResults.isEmpty()) {
                    Text("अजून data नाही. Quiz किंवा Ground attempt सुरू करा.")
                } else {
                    quizResults.takeLast(5).reversed().forEach {
                        Text("📝 ${it.category}: ${it.score}/${it.total}")
                    }
                    groundResults.takeLast(5).reversed().forEach {
                        Text("🏃 ${it.event}: ${if (it.event == "Shot Put") String.format("%.2f", it.value.toDoubleOrNull() ?: 0.0) else formatTime(parseTimeSeconds(it.value) ?: 0.0)}")
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("💡 Next Focus", fontWeight = FontWeight.Bold)
                Text(
                    when {
                        accuracy < 60 -> "MCQ accuracy वाढवण्यासाठी Mistake Book revise करा."
                        accuracy < 80 -> "80% Club साठी रोज एक focused mock द्या."
                        best1600 == null -> "1600m चा पहिला tracked attempt नोंदवा."
                        else -> "Consistency कायम ठेवा आणि Personal Best सुधारण्यावर focus करा."
                    }
                )
            }
        }
    }
}

@Composable
fun RankSystem(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var points by remember { mutableStateOf(0) }
    var name by remember { mutableStateOf("खाकी योद्धा") }

    LaunchedEffect(Unit) {
        val p = context.lakshyaDataStore.data.first()
        points = p[LEADERBOARD_POINTS] ?: 0
        name = p[LEADERBOARD_NAME] ?: "खाकी योद्धा"
    }

    val rank = when {
        points >= 1000 -> "🏆 Legend"
        points >= 700 -> "🥇 Elite"
        points >= 450 -> "🥈 Pro"
        points >= 250 -> "🥉 Advanced"
        points >= 100 -> "⭐ Starter"
        else -> "🌱 Beginner"
    }
    val next = when {
        points >= 1000 -> 1000
        points >= 700 -> 1000
        points >= 450 -> 700
        points >= 250 -> 450
        points >= 100 -> 250
        else -> 100
    }
    val previous = when {
        points >= 1000 -> 1000
        points >= 700 -> 700
        points >= 450 -> 450
        points >= 250 -> 250
        points >= 100 -> 100
        else -> 0
    }
    val progress = if (next == previous) 1f
        else ((points - previous).toFloat() / (next - previous)).coerceIn(0f, 1f)

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Rank & Points", style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold)
        Text("तुमच्या अभ्यासाच्या consistency वर आधारित local rank.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(rank, style = MaterialTheme.typography.headlineSmall)
                Text("$points XP", style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold)
                LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth())
                Text("$points / $next XP", style = MaterialTheme.typography.labelMedium)
            }
        }

        Text("XP कसा मिळेल?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        listOf(
            "📝 Quiz पूर्ण करा — +10 XP",
            "🎯 Mock Test — +20 XP",
            "🏃 Ground attempt — +15 XP",
            "🔥 Daily goal पूर्ण — +25 XP"
        ).forEach {
            Card(Modifier.fillMaxWidth()) {
                Text(it, Modifier.padding(15.dp))
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🏅 Rank Levels", fontWeight = FontWeight.Bold)
                Text("🌱 Beginner 0 • ⭐ Starter 100 • 🥉 Advanced 250")
                Text("🥈 Pro 450 • 🥇 Elite 700 • 🏆 Legend 1000+")
            }
        }
    }
}

@Composable
fun SmartPractice(nav: NavHostController) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Smart Practice", style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold)
        Text("तुमच्या Mistake Book वर आधारित revision workflow.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Card(Modifier.fillMaxWidth().clickable { nav.navigate("mistakes") }) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("📕 Mistake Revision", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold)
                Text("चुकीचे झालेले प्रश्न पुन्हा पाहा आणि योग्य उत्तराचे explanation वाचा.")
            }
        }

        Card(Modifier.fillMaxWidth().clickable { nav.navigate("quiz/All") }) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("⚡ Quick Mixed Practice", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold)
                Text("सर्व विषयांचा timed mixed test.")
            }
        }

        Card(Modifier.fillMaxWidth().clickable { nav.navigate("mocks") }) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("🎯 Topic Practice", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold)
                Text("Topic-wise tests मधून weak areas वर सराव करा.")
            }
        }
    }
}

@Composable
fun DailyGoals(){
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var mcq by remember{mutableStateOf(20)};var ground by remember{mutableStateOf(false)};var saved by remember{mutableStateOf(false)}
    LaunchedEffect(Unit){val p=context.lakshyaDataStore.data.first();mcq=p[DAILY_MCQ_GOAL]?:20;ground=p[DAILY_GROUND_GOAL]?:false}
    Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("Daily Goals",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
        Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){Text("MCQ Goal",style=MaterialTheme.typography.titleLarge);Text("$mcq questions/day");Slider(value=mcq.toFloat(), onValueChange={mcq=it.toInt()}, valueRange=5f..100f, steps=18)}}
        Card(Modifier.fillMaxWidth()){Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Ground Training",style=MaterialTheme.typography.titleLarge);Text("आज training planned?",color=MaterialTheme.colorScheme.onSurfaceVariant)};Switch(ground,{ground=it})}}
        Button({scope.launch{context.lakshyaDataStore.edit{it[DAILY_MCQ_GOAL]=mcq;it[DAILY_GROUND_GOAL]=ground};saved=true}},Modifier.fillMaxWidth()){Text(if(saved)"Saved ✓" else "Save Daily Goals")}
    }
}

@Composable
fun ReminderSettings(requestPermission:()->Unit){
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var enabled by remember{mutableStateOf(true)};var hour by remember{mutableStateOf(19)};var minute by remember{mutableStateOf(0)}
    LaunchedEffect(Unit){val p=context.lakshyaDataStore.data.first();enabled=p[REMINDER_ENABLED]?:false;hour=p[REMINDER_HOUR]?:19;minute=p[REMINDER_MINUTE]?:0}
    fun persist(){scope.launch{context.lakshyaDataStore.edit{it[REMINDER_ENABLED]=enabled;it[REMINDER_HOUR]=hour;it[REMINDER_MINUTE]=minute}}}
    Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("Reminders",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
        Card(Modifier.fillMaxWidth()){Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Daily Reminder",style=MaterialTheme.typography.titleLarge);Text(String.format(Locale.getDefault(),"%02d:%02d",hour,minute))};Switch(enabled,{enabled=it;if(it){scheduleDailyReminder(context,hour,minute);requestPermission()}else cancelDailyReminder(context);persist()})}}
        OutlinedButton({
            TimePickerDialog(context, { _, h, m ->
                hour=h; minute=m
                if(enabled) scheduleDailyReminder(context,hour,minute)
                persist()
            }, hour, minute, true).show()
        },Modifier.fillMaxWidth()){Text("🕒 Change reminder time")}
        Text("Default: 7:00 PM • Android may deliver slightly later to save battery.",color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
