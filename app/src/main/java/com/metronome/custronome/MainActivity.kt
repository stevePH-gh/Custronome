package com.metronome.custronome

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metronome.custronome.ui.theme.CustronomeTheme
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
// Helper to get actual file name from Uri
fun Uri.getFileName(context: Context): String {
    var name = ""
    val cursor = context.contentResolver.query(this, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) name = it.getString(index)
        }
    }
    return name
}

class MainActivity : ComponentActivity() {

    private var soundPool: SoundPool? = null
    private var normalTickId = 0
    private var accentTickId = 0
    private var soundLoaded = false

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var nextTick = 0L
    private var currentInterval = 0L
    private var beatIndex = 0
    private var volume = 1f  // Metronome volume
    private var mediaPlayer: MediaPlayer? = null

    // Lifted to Compose state so UI recomposes automatically
    private val _selectedAudioUri = mutableStateOf<Uri?>(null)
    val selectedAudioUri: Uri? get() = _selectedAudioUri.value

    // File picker
    private val getAudio = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            _selectedAudioUri.value = uri
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(this@MainActivity, uri)
                prepare()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        soundPool = SoundPool.Builder()
            .setAudioAttributes(attrs)
            .setMaxStreams(4)
            .build()

        normalTickId = soundPool!!.load(this, R.raw.tick, 1)
        accentTickId = soundPool!!.load(this, R.raw.tack, 1)
        soundPool!!.setOnLoadCompleteListener { _, _, status -> soundLoaded = status == 0 }

        setContent {
            CustronomeTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MetronomeUI(
                        onStart = { bpm -> startMetronome(bpm) },
                        onStop = { stopMetronome() },
                        onVolumeChange = { newVolume -> volume = newVolume },
                        onAddMusic = { getAudio.launch("audio/*") },
                        mediaPlayerProvider = { mediaPlayer },
                        selectedAudioUriProvider = { selectedAudioUri },
                        context = this
                    )
                }
            }
        }
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!running || !soundLoaded) return
            val soundId = if (beatIndex == 0) accentTickId else normalTickId
            soundPool?.play(soundId, volume, volume, 1, 0, 1f)

            beatIndex = (beatIndex + 1) % 4
            nextTick += currentInterval

            val delay = nextTick - SystemClock.uptimeMillis()
            handler.postDelayed(this, delay.coerceAtLeast(0))
        }
    }

    private fun startMetronome(bpm: Int) {
        if (running) return
        running = true
        beatIndex = 0
        currentInterval = (60000.0 / bpm).toLong()
        nextTick = SystemClock.uptimeMillis()
        handler.post(tickRunnable)
    }

    private fun stopMetronome() {
        running = false
        handler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMetronome()
        soundPool?.release()
        soundPool = null
        mediaPlayer?.release()
        mediaPlayer = null
    }
}

@Composable
fun MetronomeUI(
    onStart: (Int) -> Unit,
    onStop: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onAddMusic: () -> Unit,
    mediaPlayerProvider: () -> MediaPlayer?,
    selectedAudioUriProvider: () -> Uri?,
    context: Context
) {
    var bpm by remember { mutableStateOf(120) }
    var bpmText by remember { mutableStateOf("120") }
    var volume by remember { mutableStateOf(1f) }
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- Main Metronome Section ---
        Text("Metronome", fontSize = 28.sp, color = colors.onBackground)
        Spacer(Modifier.height(24.dp))
        Text(bpm.toString(), fontSize = 64.sp, color = colors.onBackground)
        Text("BPM", color = colors.onBackground)
        Spacer(Modifier.height(24.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Button(onClick = { onStart(bpm) }) { Text("Start", color = colors.onPrimary) }
            Button(onClick = { onStop() }) { Text("Stop", color = colors.onPrimary) }
        }

        Spacer(Modifier.height(24.dp))
        Text("Volume", color = colors.onBackground)
        Slider(
            value = volume,
            onValueChange = {
                volume = it
                onVolumeChange(it)
            },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = colors.primary,
                activeTrackColor = colors.primary
            )
        )

        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = bpmText,
                onValueChange = { bpmText = it },
                label = { Text("Enter BPM") },
                modifier = Modifier.weight(1f),
                colors = TextFieldDefaults.colors()
            )
            Spacer(Modifier.width(12.dp))
            Button(onClick = {
                bpmText.toIntOrNull()?.let { bpm = it }
                onStop()
                onStart(bpm)

            }) {
                Text("Set BPM", color = colors.onPrimary)
            }
        }

        Spacer(Modifier.height(24.dp))
        Slider(
            value = bpm.toFloat(),
            onValueChange = {
                bpm = it.toInt()
                bpmText = bpm.toString()
            },
            valueRange = 1f..300f,
            colors = SliderDefaults.colors(
                thumbColor = colors.primary,
                activeTrackColor = colors.primary
            )
        )

        // --- Advanced Section ---
        Spacer(Modifier.height(32.dp))
        Divider(color = colors.onSurface, thickness = 1.dp)
        Spacer(Modifier.height(12.dp))
        Text("Advanced", fontSize = 20.sp, color = colors.onBackground)
        Spacer(Modifier.height(16.dp))

        AdvancedMusicSection(
            context = context,
            onAddMusic = onAddMusic,
            mediaPlayerProvider = mediaPlayerProvider,
            selectedAudioUriProvider = selectedAudioUriProvider
        )
    }
}
@Composable
fun AdvancedMusicSection(
    context: Context,
    onAddMusic: () -> Unit,
    mediaPlayerProvider: () -> MediaPlayer?,
    selectedAudioUriProvider: () -> Uri?
) {
    val colors = MaterialTheme.colorScheme
    var musicVolume by remember { mutableStateOf(1f) }

    val selectedUri = selectedAudioUriProvider()
    val filename = selectedUri?.getFileName(context) ?: ""

    val mediaPlayer = mediaPlayerProvider()
    var isPlaying by remember { mutableStateOf(mediaPlayer?.isPlaying == true) }

    LaunchedEffect(mediaPlayer) {
        isPlaying = mediaPlayer?.isPlaying == true
        mediaPlayer?.setOnCompletionListener {
            isPlaying = false
            it.seekTo(0)
        }
    }

    // Track progress state
    var currentPosition by remember { mutableStateOf(0) }
    val totalDuration = mediaPlayer?.duration ?: 0

    // Update currentPosition every 500ms while playing
    LaunchedEffect(mediaPlayer, isPlaying) {
        while (isPlaying && mediaPlayer != null) {
            currentPosition = mediaPlayer.currentPosition
            kotlinx.coroutines.delay(500)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- Row with durations and Add Music ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = String.format("%02d:%02d", currentPosition / 1000 / 60, (currentPosition / 1000) % 60),
                color = colors.onBackground,
                modifier = Modifier.padding(start = 8.dp)
            )

            Button(onClick = onAddMusic) {
                Text("Add Music", color = colors.onPrimary)
            }

            Text(
                text = String.format("%02d:%02d", totalDuration / 1000 / 60, (totalDuration / 1000) % 60),
                color = colors.onBackground,
                modifier = Modifier.padding(end = 8.dp)
            )
        }

        Spacer(Modifier.height(4.dp))
        ScrollingFilename(filename)

        val buttonSize = 56.dp
        val iconSize = 28.dp

        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    val player = mediaPlayerProvider() ?: return@IconButton
                    if (isPlaying) {
                        player.pause()
                        isPlaying = false
                    } else {
                        player.start()
                        isPlaying = true
                    }
                },
                modifier = Modifier.size(buttonSize)
            ) {
                Image(
                    painter = painterResource(
                        id = if (isPlaying) R.drawable.button_pause else R.drawable.button_play
                    ),
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(iconSize),
                    contentScale = ContentScale.Fit
                )
            }

            Spacer(Modifier.width(16.dp))

            IconButton(
                onClick = {
                    mediaPlayerProvider()?.let { player ->
                        player.pause()
                        player.seekTo(0)
                    }
                    isPlaying = false
                },
                modifier = Modifier.size(buttonSize)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.button_stop),
                    contentDescription = "Stop",
                    modifier = Modifier.size(iconSize),
                    contentScale = ContentScale.Fit
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Text("Music Volume", color = colors.onBackground)
        Slider(
            value = musicVolume,
            onValueChange = {
                musicVolume = it
                mediaPlayerProvider()?.setVolume(it, it)
            },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = colors.primary,
                activeTrackColor = colors.primary
            )
        )
    }
}

@Composable
fun ScrollingFilename(filename: String) {
    if (filename.isEmpty()) return

    val textWidthPx = remember { mutableStateOf(0f) }
    val boxWidthPx = remember { mutableStateOf(0f) }

    val speed = 120f // pixels per second
    val pauseMillis = 1000L

    val offsetX = remember { Animatable(0f) }

    LaunchedEffect(textWidthPx.value, boxWidthPx.value) {
        if (textWidthPx.value == 0f || boxWidthPx.value == 0f) return@LaunchedEffect

        // Step 1: Start centered for the first time
        offsetX.snapTo((boxWidthPx.value - textWidthPx.value) / 2)
        kotlinx.coroutines.delay(pauseMillis)

        while (true) {
            // Step 2: Slide fully to the left
            val targetLeft = -textWidthPx.value
            val distance = offsetX.value - targetLeft
            val duration = (distance / speed * 1000).toInt()
            offsetX.animateTo(targetValue = targetLeft, animationSpec = tween(durationMillis = duration, easing = LinearEasing))

            // Step 3: Reset off-screen right
            offsetX.snapTo(boxWidthPx.value)

            // Step 4: Slide across to the left
            val totalDistance = boxWidthPx.value + textWidthPx.value
            val totalDuration = (totalDistance / speed * 1000).toInt()
            offsetX.animateTo(targetValue = -textWidthPx.value, animationSpec = tween(durationMillis = totalDuration, easing = LinearEasing))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clipToBounds()
            .onGloballyPositioned { coordinates ->
                boxWidthPx.value = coordinates.size.width.toFloat()
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = filename,
            color = Color.Gray,
            fontSize = 14.sp,
            softWrap = false,
            maxLines = 1,
            modifier = Modifier
                .onGloballyPositioned { coordinates ->
                    textWidthPx.value = coordinates.size.width.toFloat()
                }
                .offset { IntOffset(offsetX.value.toInt(), 0) }
        )
    }
}
