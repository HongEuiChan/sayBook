package com.saybook.subway.android

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saybook.subway.model.Direction
import com.saybook.subway.model.SubwayLine
import com.saybook.subway.model.SubwayLines
import com.saybook.subway.track.AlertTexts
import com.saybook.subway.track.TrackerState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    val state by TrackingRepository.state.collectAsStateWithLifecycle()
                    Column(Modifier.safeDrawingPadding().padding(16.dp)) {
                        PermissionBar()
                        val current = state
                        if (current != null) TrackingScreen(current) else SetupScreen()
                    }
                }
            }
        }
    }
}

private fun SubwayLine.color() = Color(0xFF000000 or colorHex)

@Composable
private fun PermissionBar() {
    val context = LocalContext.current
    var canOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { canOverlay = Settings.canDrawOverlays(context) }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (!canOverlay) {
        Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("영상 위에 역 이름을 띄우려면", fontWeight = FontWeight.Bold)
                Text("'다른 앱 위에 표시' 권한이 필요합니다. 없으면 알림 배너로만 표시돼요.", fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
                    )
                }) { Text("권한 허용하기") }
            }
        }
    }
}

@Composable
private fun ColumnScope.SetupScreen() {
    val context = LocalContext.current
    var lineId by rememberSaveable { mutableStateOf(SubwayLines.all.first().id) }
    var direction by rememberSaveable { mutableStateOf(Direction.FORWARD) }
    var stationIndex by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var demo by rememberSaveable { mutableStateOf(false) }
    val line = SubwayLines.byId(lineId) ?: SubwayLines.all.first()

    Text("지하철 역알림", fontSize = 24.sp, fontWeight = FontWeight.Bold)
    Text("영상을 보는 동안 정차하는 역을 화면 위에 알려드려요", fontSize = 13.sp, color = Color.Gray)
    Spacer(Modifier.height(16.dp))

    Text("노선", fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SubwayLines.all.forEach { l ->
            FilterChip(
                selected = l.id == lineId,
                onClick = { lineId = l.id; stationIndex = 0; query = "" },
                label = { Text(l.name) },
                leadingIcon = { Box(Modifier.size(10.dp).background(l.color(), CircleShape)) },
            )
        }
    }

    Text("방향", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Direction.entries.forEach { d ->
            FilterChip(selected = d == direction, onClick = { direction = d }, label = { Text(line.directionLabel(d)) })
        }
    }

    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text("탄 역 검색") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )

    val listState = rememberLazyListState()
    val filtered = line.stations.withIndex().filter { query.isBlank() || it.value.name.contains(query.trim()) }
    LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
        itemsIndexed(filtered, key = { _, s -> "${line.id}-${s.index}" }) { _, (index, station) ->
            val selected = index == stationIndex
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { stationIndex = index }
                    .background(if (selected) line.color().copy(alpha = 0.15f) else Color.Transparent)
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).background(line.color(), CircleShape))
                Spacer(Modifier.size(12.dp))
                Text(station.name, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            }
            HorizontalDivider()
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
        Switch(checked = demo, onCheckedChange = { demo = it })
        Spacer(Modifier.size(8.dp))
        Text("데모 모드 (가상 센서 데이터, 10배속)", fontSize = 13.sp)
    }

    val start = line.stations[stationIndex].name
    Button(
        onClick = { TrackingService.start(context, line.id, stationIndex, direction, demo) },
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) { Text("$start 에서 출발 · ${line.directionLabel(direction)}") }
}

@Composable
private fun ColumnScope.TrackingScreen(state: TrackerState) {
    val context = LocalContext.current
    val (title, subtitle) = AlertTexts.compact(state)

    Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center) {
        Text("${state.line.name} · ${state.directionLabel}", color = state.line.color(), fontWeight = FontWeight.Bold)
        Text(title, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold)
        Text(subtitle, fontSize = 16.sp, color = Color.Gray)
        Spacer(Modifier.height(24.dp))
        Text("지나온 역: ${state.stationsPassed}개", fontSize = 14.sp)
        Spacer(Modifier.height(24.dp))
        Text(
            "센서 · 진동 %.3f m/s² · 가감속 %.3f m/s² · %s".format(
                state.features.vibrationRms, state.features.horizontalAccel, state.features.motionClass.name,
            ),
            fontSize = 11.sp,
            color = Color.Gray,
        )
        Text("이제 영상 앱으로 이동해도 계속 알려드려요.", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
    }

    Text("감지가 틀렸다면 직접 보정하세요", fontSize = 12.sp, color = Color.Gray)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { TrackingService.send(context, TrackingService.ACTION_PREV) }, Modifier.weight(1f)) {
            Text("◀ 이전 역")
        }
        OutlinedButton(onClick = { TrackingService.send(context, TrackingService.ACTION_NEXT) }, Modifier.weight(1f)) {
            Text("다음 역 ▶")
        }
    }
    TextButton(
        onClick = { TrackingService.send(context, TrackingService.ACTION_STOP) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("알림 종료") }
}
