package com.lhcomics.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lhcomics.app.ui.browse.BrowseScreen
import com.lhcomics.app.ui.home.HomeScreen
import com.lhcomics.app.ui.settings.SettingsScreen
import com.lhcomics.app.ui.source.SourceEditScreen
import com.lhcomics.app.ui.viewer.ViewerScreen

val Context.app: LhApp get() = applicationContext as LhApp

class MainActivity : ComponentActivity() {
    /** 뷰어가 열려 있는 동안 볼륨 키를 페이지 넘김으로 쓴다. 처리했으면 true. */
    var volumeKeyHandler: ((up: Boolean) -> Boolean)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        app.ads.start(this)
        setContent { LhTheme { AppNavigation() } }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handler = volumeKeyHandler
        if (handler != null && (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            // 길게 눌러도 한 장씩 넘어가도록 ACTION_DOWN만 처리하고 UP은 삼킨다.
            if (event.action == KeyEvent.ACTION_DOWN && handler(event.keyCode == KeyEvent.KEYCODE_VOLUME_UP)) return true
            if (event.action == KeyEvent.ACTION_UP) return true
        }
        return super.dispatchKeyEvent(event)
    }
}

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"

    fun editSource(id: String? = null) = "source/edit?id=${id.orEmpty()}"
    fun browse(sourceId: String, location: String) = "browse/$sourceId?loc=${Uri.encode(location)}"
    fun viewer(sourceId: String, location: String, page: Int = -1) =
        "viewer/$sourceId?loc=${Uri.encode(location)}&page=$page"
}

@Composable
private fun AppNavigation() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) { HomeScreen(nav) }
        composable(Routes.SETTINGS) { SettingsScreen(nav) }
        composable(
            "source/edit?id={id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType; defaultValue = "" }),
        ) { entry ->
            SourceEditScreen(nav, entry.arguments?.getString("id").orEmpty().ifEmpty { null })
        }
        composable(
            "browse/{sourceId}?loc={loc}",
            arguments = listOf(
                navArgument("sourceId") { type = NavType.StringType },
                navArgument("loc") { type = NavType.StringType; defaultValue = "/" },
            ),
        ) { entry ->
            BrowseScreen(nav, entry.arguments!!.getString("sourceId")!!, entry.arguments!!.getString("loc") ?: "/")
        }
        composable(
            "viewer/{sourceId}?loc={loc}&page={page}",
            arguments = listOf(
                navArgument("sourceId") { type = NavType.StringType },
                navArgument("loc") { type = NavType.StringType },
                navArgument("page") { type = NavType.IntType; defaultValue = -1 },
            ),
        ) { entry ->
            val args = entry.arguments!!
            ViewerScreen(nav, args.getString("sourceId")!!, args.getString("loc")!!, args.getInt("page"))
        }
    }
}

@Composable
fun LhTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFF9FA8DA), secondary = Color(0xFFFFCC80))
    } else {
        lightColorScheme(primary = Color(0xFF3949AB), secondary = Color(0xFFEF6C00))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
