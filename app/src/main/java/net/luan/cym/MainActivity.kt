package net.luan.cym

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import android.widget.*
import androidx.preference.Preference
import com.google.android.material.bottomnavigation.BottomNavigationView
import net.luan.cym.ui.StatsFragment
import java.time.LocalDate
import java.util.*
import androidx.appcompat.app.AlertDialog
import net.luan.cym.util.AlarmLoggerReceiver
import net.luan.cym.util.AlarmNotificationReceiver
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

class MainActivity : AppCompatActivity() {
    private lateinit var sharedPref: SharedPreferences

    private lateinit var statsFragment: StatsFragment

    private lateinit var monthMap: HashMap<String, Int>
    private var phoneNumberToName =  HashMap<String, String>()
    private var contactNameToIndex =  HashMap<String, Int>()

    private lateinit var mAlarmManager: AlarmManager
    private lateinit var mNotificationReceiverPendingIntent: PendingIntent
    private lateinit var mLoggerReceiverPendingIntent: PendingIntent

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // UI Components
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        val title = findViewById<TextView>(R.id.title)

        // SharedPreference handler
        sharedPref = applicationContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        editor = sharedPref.edit()
        Log.d(TAG, "----- Main Activity -----")
        Log.d(TAG, "--- SHARED PREFERENCES ---")
        Log.d(TAG, "MODE: \t${sharedPref.getBoolean("WHITELISTING_MODE", false)}")
        Log.d(TAG, "FIRST: \t${sharedPref.getBoolean("FIRST", true)}")
        Log.d(TAG, "FREQ: \t${sharedPref.getInt("REMINDER_FREQ", 0)}")
        Log.d(TAG, "FRAGMENT: ${sharedPref.getInt("FRAGMENT", 0)}")
        Log.d(TAG, "--------------------------")

        // changes the fragment to the currently selected item
        bottomNav.setOnNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.stats -> {
                    statsFragment = StatsFragment()
                    supportFragmentManager.beginTransaction().replace(R.id.main_frame, statsFragment)
                        .commit()
                    title.text = resources.getString(R.string.statistics)
                }
                R.id.call -> {
                    editor.putBoolean("WHITELISTING_MODE", false)

                    if (sharedPref.getBoolean("FIRST", true)) {
                        editor.putBoolean("FIRST", false)
                        editor.putBoolean("WHITELISTING_MODE", true)
                    }

                    editor.apply()
                    bottomNav.selectedItemId = sharedPref.getInt("FRAGMENT", 0)

                    val intent = Intent(this, ContactManagerActivity::class.java)
                    startActivity(intent)
                }
                R.id.settings -> {
                    supportFragmentManager.beginTransaction().replace(R.id.main_frame, SettingsFragment())
                        .commit()
                    title.text = resources.getString(R.string.settings)
                }
            }
            true
        }

        // default page is call page
        bottomNav.selectedItemId = R.id.call

        allContacts = readCallLog(this)
        // read contact log, make updated contact list
        var updatedContacts: ArrayList<Contact> = ArrayList()
        // check for whitelist sharedPref
        for (contact in allContacts) {
            if (contact.name != "") {
                var whitelisted = sharedPref.getBoolean(contact.name, false)
                contact.changeWhitelist(whitelisted)
                updatedContacts.add(contact)
            } else {
                // do nothing
            }
        }
        allContacts = updatedContacts

        // notification stuff
        // Create an Intent to broadcast to the AlarmNotificationReceiver
        val mNotificationReceiverIntent = Intent(
            this@MainActivity,
            AlarmNotificationReceiver::class.java
        )

        // Create an PendingIntent that holds the NotificationReceiverIntent
        mNotificationReceiverPendingIntent = PendingIntent.getBroadcast(
            this@MainActivity, 0, mNotificationReceiverIntent, 0
        )

        // Create an Intent to broadcast to the AlarmLoggerReceiver
        val mLoggerReceiverIntent = Intent(
            this@MainActivity,
            AlarmLoggerReceiver::class.java
        )

        // Create PendingIntent that holds the mLoggerReceiverPendingIntent
        mLoggerReceiverPendingIntent = PendingIntent.getBroadcast(
            this@MainActivity, 0, mLoggerReceiverIntent, 0
        )

        // go through contact list and look for white listed contacts
        mAlarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (contact in allContacts) {
            Log.i(TAG, "Going through contact ${contact.name}")

            if (contact.whitelisted) {
                Log.i(TAG, "${contact.name} IS whitelisted")

                // check to if the last time contacted is greater than remainder time
                val intended_reminder = contact.last_contacted.plusDays(contact.alert_pref.toLong())
                if( intended_reminder < LocalDate.now() ) {
                    Log.i(TAG, "SENDING ALERT FOR ${contact.name}")

                    // if intended reminder is greater than the current LocalDate, send a reminder
                    mAlarmManager.set(
                        AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis(),
                        mNotificationReceiverPendingIntent
                    )

                    // Set single alarm to fire shortly after previous alarm
                    mAlarmManager.set(
                        AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + 1000L,
                        mLoggerReceiverPendingIntent
                    )

                    // Show Toast message
                    Toast.makeText(
                        applicationContext, "You need to call ${contact.name}, last time you " +
                                "called them was ${contact.last_contacted}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                Log.i(TAG, "${contact.name} is NOT whitelisted")
            }
        }
    }

    // ----- Settings Fragment -----
    class SettingsFragment : PreferenceFragmentCompat() {
        private lateinit var sharedPref: SharedPreferences
        private lateinit var editor: SharedPreferences.Editor

        // load preference xml
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            addPreferencesFromResource(R.xml.preferences)
        }

        // preference screen onClickListener
        override fun onPreferenceTreeClick(preference: Preference?): Boolean {
            sharedPref =
                activity!!.applicationContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            editor = sharedPref.edit()

            // small if statement for each preference option
            if (preference == findPreference("DEFAULT_TIME")) {
                // change default time
                Log.d(TAG, "Changing default time (was ${sharedPref.getInt("REMINDER_FREQ", 0)})")

                val items = arrayOf(
                    "Everyday", "Every other day", "Weekly",
                    "Bi-Weekly", "Monthly"
                )
                val builder = AlertDialog.Builder(activity!!)
                with(builder) {
                    setTitle("Pick reminder frequency")
                    setItems(items) { _, freq ->
                        when (freq) {
                            0 -> editor.putInt("REMINDER_FREQ", 1)
                            1 -> editor.putInt("REMINDER_FREQ", 2)
                            2 -> editor.putInt("REMINDER_FREQ", 7)
                            3 -> editor.putInt("REMINDER_FREQ", 14)
                            4 -> editor.putInt("REMINDER_FREQ", 30)
                            else -> {
                                Log.d(TAG, "FREQUENCY PICKER ERROR: Auto selecting 7 days")
                                editor.putInt("REMINDER_FREQ", 7)
                            }
                        }

                        editor.apply()
                        Log.d(TAG, "\t${sharedPref.getInt("REMINDER_FREQ", 0)}")
                    }
                    show()
                }
            } else if (preference == findPreference("CHANGE_WHITELIST")) {
                // open call log in whitelist mode
                Log.d(TAG, "Changing whitelist")
                editor.putBoolean("WHITELISTING_MODE", true)
                editor.apply()

                val intent = Intent(context, ContactManagerActivity::class.java)
                startActivity(intent)
            } else if (preference == findPreference("")) {
                Log.w(TAG, "ERROR IN PREFERENCE SELECTION")
            }

            return true
        }
    }

    override fun onResume() {
        super.onResume()

        Log.d(TAG, "--- MainActivity.onResume() ---")
        // changing bottomNav to selected activity
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        bottomNav.selectedItemId = sharedPref.getInt("FRAGMENT", 0)
    }


    // DO NOT TOUCH. ANYTHING PAST THIS WILL CRASH
    // no one knows what any of his code does... missing comments
    // ----- HAMID -----
    private fun createMap(): HashMap<String, Int> {
        monthMap = HashMap()
        monthMap[getString(R.string.Jan)] = 1; monthMap[getString(R.string.Feb)] = 2; monthMap[getString(R.string.Mar)] = 3
        monthMap[getString(R.string.Apr)] = 4; monthMap[getString(R.string.May)] = 5; monthMap[getString(R.string.Jun)] = 6
        monthMap[getString(R.string.Jul)] = 7; monthMap[getString(R.string.Aug)] = 8; monthMap[getString(R.string.Sep)] = 9
        monthMap[getString(R.string.Oct)] = 10; monthMap[getString(R.string.Nov)] = 11; monthMap[getString(R.string.Dec)] = 12

        return monthMap
    }

    private fun readCallLog(context: Context) : ArrayList<Contact>{
        val allProcessedContacts = ArrayList<Contact>()
        val contentUri = CallLog.Calls.CONTENT_URI

        try {
            val cursor = context.contentResolver.query(contentUri, null, null, null, null)
            val nameUri = cursor!!.getColumnIndex(CallLog.Calls.CACHED_LOOKUP_URI)
            val number = cursor.getColumnIndex(CallLog.Calls.NUMBER)
            val date = cursor.getColumnIndex(CallLog.Calls.DATE)
            //testing whether the query result is empty
            val isNonEmptyQueryResult = cursor.moveToFirst()
            contactNameToIndex.clear()
            createMap()

            if (isNonEmptyQueryResult) {
                do {
                    var isPreviouslySeenContact = false
                    val callerNameUriFormat = cursor.getString(nameUri)
                    val phoneNumber = cursor.getString(number).toLong()
                    val callDate = cursor.getString(date)
                    val dateString = Date(callDate.toLong()).toString()
                    val year = dateString.substring(dateString.length - 4).toInt()
                    val month = monthMap.get(dateString.substring(4, 7))
                    val day = dateString.substring(8, 10).toInt()
                    val lastContacted: LocalDate = LocalDate.of(year, month!!, day)
                    val name: String

                    if (phoneNumberToName[phoneNumber.toString()] == null) {
                        name = getContactName(phoneNumber.toString(), context)
                    } else if (callerNameUriFormat == null) {
                        name = phoneNumberToName[phoneNumber.toString()]!!
                    } else {
                        name = getNameFromUriFormat(callerNameUriFormat)
                    }

                    //Node: the default alert_pref is set to 7
                    val alert_pref = sharedPref.getInt("REMINDER_FREQ", 7)
                    val contact = Contact(name, phoneNumber, 1, lastContacted, alert_pref, false)

                    //if we've seen this contact before
                    if (contactNameToIndex[name] != null) {
                        val indexOfContact = contactNameToIndex[name]
                        allProcessedContacts[indexOfContact!!].freq += 1
                        allProcessedContacts[indexOfContact!!].last_contacted = lastContacted
                        isPreviouslySeenContact = true
                    } else {
                        contactNameToIndex[name] = allProcessedContacts.size
                    }

                    if (!isPreviouslySeenContact) {
                        allProcessedContacts.add(contact)
                        phoneNumberToName[phoneNumber.toString()] = name
                    }
                } while (cursor.moveToNext())
            }
            cursor.close()
        } catch (e : SecurityException) {
            Log.w(TAG, e.toString())
        }

        return allProcessedContacts
    }

    private fun getNameFromUriFormat(nameInUri : String?) : String {
        return if (nameInUri != null) {
            val cursor = contentResolver.query(Uri.parse(nameInUri), null, null, null, null)

            var name = ""
            if ((cursor?.count ?: 0) > 0) {
                while (cursor != null && cursor.moveToNext()) {
                    name = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME))
                }
            }
            cursor?.close()

            return name
        } else {
            "Unknown Number"
        }
    }

    fun getContactName(phoneNumber: String, context: Context): String {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber)
        )

        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
        var contactName = ""
        val cursor = context.contentResolver.query(uri, projection, null, null, null)

        if (cursor != null) {
            if (cursor.moveToFirst()) {
                contactName = cursor.getString(0)
            }
            cursor.close()
        }

        return contactName
    }
    // ------ END ------



    companion object {
        private val TAG = "CYM-Debug"

        private val PREF_FILE = "net.luan.cym.prefs"

        lateinit var allContacts: ArrayList<Contact>
        fun returnContactList(): ArrayList<Contact> {
            return allContacts
        }

        private lateinit var editor: SharedPreferences.Editor
    }
}