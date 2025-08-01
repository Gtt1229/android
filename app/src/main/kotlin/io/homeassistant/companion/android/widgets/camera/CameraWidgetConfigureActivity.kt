package io.homeassistant.companion.android.widgets.camera

import android.R
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Spinner
import android.widget.Toast
import androidx.core.content.getSystemService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.homeassistant.companion.android.common.R as commonR
import io.homeassistant.companion.android.common.data.integration.Entity
import io.homeassistant.companion.android.common.data.integration.domain
import io.homeassistant.companion.android.database.widget.CameraWidgetDao
import io.homeassistant.companion.android.database.widget.WidgetTapAction
import io.homeassistant.companion.android.databinding.WidgetCameraConfigureBinding
import io.homeassistant.companion.android.settings.widgets.ManageWidgetsViewModel
import io.homeassistant.companion.android.util.applySafeDrawingInsets
import io.homeassistant.companion.android.widgets.BaseWidgetConfigureActivity
import io.homeassistant.companion.android.widgets.common.SingleItemArrayAdapter
import javax.inject.Inject
import kotlinx.coroutines.launch
import timber.log.Timber

@AndroidEntryPoint
class CameraWidgetConfigureActivity : BaseWidgetConfigureActivity() {

    companion object {
        private const val PIN_WIDGET_CALLBACK =
            "io.homeassistant.companion.android.widgets.camera.CameraWidgetConfigureActivity.PIN_WIDGET_CALLBACK"
    }

    private lateinit var binding: WidgetCameraConfigureBinding

    override val serverSelect: View
        get() = binding.serverSelect

    override val serverSelectList: Spinner
        get() = binding.serverSelectList

    private var requestLauncherSetup = false

    private var entities = mutableMapOf<Int, List<Entity>>()
    private var selectedEntity: Entity? = null

    @Inject
    lateinit var cameraWidgetDao: CameraWidgetDao
    override val dao get() = cameraWidgetDao

    private var entityAdapter: SingleItemArrayAdapter<Entity>? = null

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set the result to CANCELED.  This will cause the widget host to cancel
        // out of the widget placement if the user presses the back button.
        setResult(RESULT_CANCELED)

        binding = WidgetCameraConfigureBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySafeDrawingInsets()

        binding.addButton.setOnClickListener {
            if (requestLauncherSetup) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isValidServerId() && selectedEntity != null) {
                    getSystemService<AppWidgetManager>()?.requestPinAppWidget(
                        ComponentName(this, CameraWidget::class.java),
                        null,
                        PendingIntent.getActivity(
                            this,
                            System.currentTimeMillis().toInt(),
                            Intent(
                                this,
                                CameraWidgetConfigureActivity::class.java,
                            ).putExtra(PIN_WIDGET_CALLBACK, true).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                        ),
                    )
                } else {
                    showAddWidgetError()
                }
            } else {
                onAddWidget()
            }
        }

        // Find the widget id from the intent.
        val intent = intent
        val extras = intent.extras
        if (extras != null) {
            appWidgetId = extras.getInt(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            )
            requestLauncherSetup = extras.getBoolean(
                ManageWidgetsViewModel.CONFIGURE_REQUEST_LAUNCHER,
                false,
            )
        }

        // If this activity was started with an intent without an app widget ID, finish with an error.
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID && !requestLauncherSetup) {
            finish()
            return
        }
        initTapActionsSpinner()

        lifecycleScope.launch {
            val cameraWidget = cameraWidgetDao.get(appWidgetId)
            if (cameraWidget != null) {
                setCurrentTapAction(tapAction = cameraWidget.tapAction)
                binding.widgetTextConfigEntityId.setText(cameraWidget.entityId)
                binding.addButton.setText(commonR.string.update_widget)
                val entity = try {
                    serverManager.integrationRepository(cameraWidget.serverId).getEntity(cameraWidget.entityId)
                } catch (e: Exception) {
                    Timber.e(e, "Unable to get entity information")
                    Toast.makeText(applicationContext, commonR.string.widget_entity_fetch_error, Toast.LENGTH_LONG)
                        .show()
                    null
                }

                if (entity != null) {
                    selectedEntity = entity as Entity?
                }
            }

            setupServerSelect(cameraWidget?.serverId)
        }

        entityAdapter = SingleItemArrayAdapter(this) { it?.entityId ?: "" }

        binding.widgetTextConfigEntityId.setAdapter(entityAdapter)
        binding.widgetTextConfigEntityId.onFocusChangeListener = dropDownOnFocus
        binding.widgetTextConfigEntityId.onItemClickListener = entityDropDownOnItemClick

        serverManager.defaultServers.forEach { server ->
            lifecycleScope.launch {
                try {
                    val fetchedEntities = serverManager.integrationRepository(server.id).getEntities().orEmpty()
                        .filter { it.domain == "camera" || it.domain == "image" }
                    entities[server.id] = fetchedEntities
                    if (server.id == selectedServerId) setAdapterEntities(server.id)
                } catch (e: Exception) {
                    // If entities fail to load, it's okay to pass
                    // an empty map to the dynamicFieldAdapter
                    Timber.e(e, "Failed to query entities")
                }
            }
        }
    }

    override fun onServerSelected(serverId: Int) {
        selectedEntity = null
        binding.widgetTextConfigEntityId.setText("")
        setAdapterEntities(serverId)
    }

    private fun setAdapterEntities(serverId: Int) {
        entityAdapter?.let { adapter ->
            adapter.clearAll()
            if (entities[serverId] != null) {
                adapter.addAll(entities[serverId].orEmpty().toMutableList())
                adapter.sort()
            }
            runOnUiThread { adapter.notifyDataSetChanged() }
        }
    }

    private val dropDownOnFocus = View.OnFocusChangeListener { view, hasFocus ->
        if (hasFocus && view is AutoCompleteTextView) {
            view.showDropDown()
        }
    }

    private val entityDropDownOnItemClick =
        AdapterView.OnItemClickListener { parent, _, position, _ ->
            selectedEntity = parent.getItemAtPosition(position) as Entity?
        }

    private fun onAddWidget() {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            showAddWidgetError()
            return
        }
        try {
            val context = this@CameraWidgetConfigureActivity

            // Set up a broadcast intent and pass the service call data as extras
            val intent = Intent()
            intent.action = CameraWidget.RECEIVE_DATA
            intent.component = ComponentName(context, CameraWidget::class.java)

            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)

            intent.putExtra(
                CameraWidget.EXTRA_SERVER_ID,
                selectedServerId!!,
            )
            intent.putExtra(
                CameraWidget.EXTRA_ENTITY_ID,
                selectedEntity!!.entityId,
            )
            intent.putExtra(
                CameraWidget.EXTRA_TAP_ACTION,
                if (binding.tapActionList.selectedItemPosition == 0) WidgetTapAction.REFRESH else WidgetTapAction.OPEN,
            )

            context.sendBroadcast(intent)

            // Make sure we pass back the original appWidgetId
            setResult(
                RESULT_OK,
                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
            )
            finish()
        } catch (e: Exception) {
            Timber.e(e, "Issue configuring widget")
            showAddWidgetError()
        }
    }

    private fun initTapActionsSpinner() {
        val tapActionValues =
            listOf(getString(commonR.string.refresh), getString(commonR.string.widget_tap_action_open))
        binding.tapActionList.adapter = ArrayAdapter(this, R.layout.simple_spinner_dropdown_item, tapActionValues)
    }

    private fun setCurrentTapAction(tapAction: WidgetTapAction) {
        binding.tapActionList.setSelection(if (tapAction == WidgetTapAction.REFRESH) 0 else 1)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.extras != null && intent.hasExtra(PIN_WIDGET_CALLBACK)) {
            appWidgetId = intent.extras!!.getInt(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            )
            onAddWidget()
        }
    }
}
