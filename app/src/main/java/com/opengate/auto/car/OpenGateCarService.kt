package com.opengate.auto.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.opengate.auto.GateStore

/** נקודת הכניסה של Android Auto. */
class OpenGateCarService : CarAppService() {

    // אפליקציה פרטית בהתקנה ידנית - מאשרים כל host.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = GatesScreen(carContext)
    }
}

/** מסך הרכב: רשימת השערים, לחיצה על שורה פותחת את השער. */
class GatesScreen(carContext: CarContext) : Screen(carContext) {

    init {
        // רענון הרשימה בכל חזרה למסך, למקרה שהשערים נערכו בטלפון.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = invalidate()
        })
    }

    override fun onGetTemplate(): Template {
        val items = ItemList.Builder()
            .setNoItemsMessage("אין שערים. הוסף שערים באפליקציה בטלפון.")

        // Android Auto מגביל רשימות ל-6 שורות ברוב הרכבים.
        GateStore.load(carContext).take(6).forEach { gate ->
            items.addItem(
                Row.Builder()
                    .setTitle(gate.name)
                    .addText(when { gate.isApi -> "OpenGate"; gate.isHttp -> "רשת"; else -> gate.target })
                    .setOnClickListener {
                        GateStore.open(carContext, gate) { msg ->
                            CarToast.makeText(carContext, msg, CarToast.LENGTH_SHORT).show()
                        }
                    }
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setTitle("OpenGate")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(items.build())
            .build()
    }
}
