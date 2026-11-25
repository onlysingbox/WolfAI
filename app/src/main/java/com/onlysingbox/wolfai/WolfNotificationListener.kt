package com.onlysingbox.wolfai

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

data class WolfNotification(val app:String,val title:String,val text:String,val time:String)

class WolfNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val item=sbn ?: return
        val extras=item.notification.extras ?: return
        val title=extras.getCharSequence("android.title")?.toString().orEmpty()
        val text=(extras.getCharSequence("android.bigText") ?: extras.getCharSequence("android.text"))?.toString().orEmpty()
        if(title.isBlank() && text.isBlank()) return
        val app=runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(item.packageName,0)).toString() }.getOrDefault(item.packageName)
        val time=SimpleDateFormat("HH:mm",Locale.getDefault()).format(Date(item.postTime))
        synchronized(recent) {
            recent.add(0,WolfNotification(app,title.take(160),text.take(600),time))
            while(recent.size>100) recent.removeAt(recent.lastIndex)
        }
    }

    companion object {
        val recent=CopyOnWriteArrayList<WolfNotification>()
    }
}
