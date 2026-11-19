// Adapted from the Android Dice Roller codelab. The upstream code is copyright
// 2023 The Android Open Source Project and licensed under Apache-2.0.
// Combo Dice adds five-dice rolls, combination scoring, and audiovisual feedback.
package com.combodice.diceroller

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.combodice.diceroller.ui.theme.DiceRollerTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.delay
import kotlin.random.Random
import nl.dionsegijn.konfetti.compose.KonfettiView
import nl.dionsegijn.konfetti.core.Position
import nl.dionsegijn.konfetti.core.Angle
import nl.dionsegijn.konfetti.core.Party
import nl.dionsegijn.konfetti.core.emitter.Emitter
import nl.dionsegijn.konfetti.core.models.Shape
import nl.dionsegijn.konfetti.core.models.Size
import java.util.concurrent.TimeUnit
import androidx.compose.animation.core.*

// Stores high score combinations
private val HIGH_SCORE_COMBINATIONS = listOf(
    "Five of a Kind", "Four of a Kind", "Full House", "Large Straight"
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DiceRollerTheme {
                DiceRollerApp()
            }
        }
    }
}

// UI core
@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun DiceWithButtonAndImage(modifier: Modifier = Modifier) {
    // BoxWithConstraints to get screen height
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // Calculate offsets for dice rows based on screen height
        val screenHeight = maxHeight
        val backRowOffset = screenHeight * 0.38f
        val frontRowOffset = screenHeight * 0.25f
        val overlayOffset = screenHeight * 0.15f

        // Game state variables
        val taunts = listOf(
            "Try again, lucky champ!",
            "Better roll next time! 😅",
            "That's not it chief!",
            "Shake harder, maybe it'll help!",
            "The dice are not impressed 😐"
        )
        var diceResults by remember { mutableStateOf(List(5) { (1..6).random() }) }
        var isRolling by remember { mutableStateOf(false) }
        var combinationResult by remember { mutableStateOf("") }
        var showOverlay by remember { mutableStateOf(false) }
        var confettiTrigger by remember { mutableStateOf(false) }
        var showTaunt by remember { mutableStateOf(false) }
        var currentTaunt by remember { mutableStateOf("") }

        // Animated angles for dice
        val animatedAngles = remember {
            List(5) { Animatable(0f) }
        }

        // System services
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val vibrator = context.getSystemService(Vibrator::class.java)

        // Sound Setup
        val soundPool = remember {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            SoundPool.Builder()
                .setAudioAttributes(audioAttributes)
                .setMaxStreams(1)
                .build()
        }
        var soundId by remember { mutableIntStateOf(0) }
        var soundLoaded by remember { mutableStateOf(false) }

        // Load sound effect on composition
        DisposableEffect(Unit) {
            soundId = soundPool.load(context, R.raw.dice_roll, 1)
            soundPool.setOnLoadCompleteListener { _, sampleId, status ->
                if (sampleId == soundId && status == 0) {
                    soundLoaded = true
                }
            }
            onDispose {
                soundPool.release()
            }
        }

        // Roll dice
        LaunchedEffect(isRolling) {
            if (isRolling) {
                // Vibration
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(800, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(800)
                }

                // Shake and roll
                var intensity = 128f
                repeat(20) {
                    diceResults = List(5) { (1..6).random() }
                    val newAngles = List(5) { Random.nextFloat() * intensity * 2 - intensity }
                    animatedAngles.forEachIndexed { i, anim ->
                        launch {
                            anim.animateTo(
                                newAngles[i],
                                animationSpec = tween(durationMillis = 50, easing = LinearEasing)
                            )
                        }
                    }
                    intensity *= 0.85f
                    delay(50)
                }

                diceResults = List(5) { (1..6).random() }
                // Reset angles
                animatedAngles.forEach { anim ->
                    launch {
                        anim.animateTo(0f, animationSpec = tween(300))
                    }
                }

                // Combination + overlay
                combinationResult = evaluateDice(diceResults)
                val isHighScore = combinationResult in HIGH_SCORE_COMBINATIONS
                confettiTrigger = isHighScore
                showTaunt = !isHighScore
                currentTaunt = if (!isHighScore) taunts.random() else ""
                showOverlay = true

                delay(2500)
                showOverlay = false
                confettiTrigger = false
                isRolling = false
            }
        }

        // Shake detection
        DisposableEffect(lifecycleOwner) {
            val shakeDetector = ShakeDetector(context) {
                if (!isRolling && soundLoaded) {
                    isRolling = true
                    soundPool.play(soundId, 1f, 1f, 1, 0, 1f)
                }
            }

            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) shakeDetector.start()
                else if (event == Lifecycle.Event.ON_PAUSE) shakeDetector.stop()
            }

            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                shakeDetector.stop()
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }

        // Background image
        Image(
            painter = painterResource(id = R.drawable.dice_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        Box(modifier = Modifier.fillMaxSize()) {

            // Dice display back
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = backRowOffset),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                diceResults.take(3).forEachIndexed { index, result ->
                    DiceImage(result = result, size = 105.dp, rotation = animatedAngles[index].value)
                }
            }

            // Dice display front
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = frontRowOffset),
                horizontalArrangement = Arrangement.spacedBy(36.dp)
            ) {
                diceResults.drop(3).forEachIndexed { index, result ->
                    DiceImage(result = result, size = 125.dp, rotation = animatedAngles[index + 3].value)
                }
            }

            // Roll button
            Button(
                onClick = {
                    if (!isRolling && soundLoaded) {
                        isRolling = true
                        soundPool.play(soundId, 1f, 1f, 1, 0, 1f)
                    }
                },
                enabled = !isRolling,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
                    .width(180.dp)
                    .height(56.dp)
            ) {
                if (isRolling) {

                    // Loading animation
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Text("🎲 " + stringResource(R.string.roll), fontSize = 20.sp)
                }
            }
        }

        // Result overlay
        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize().zIndex(1f)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .wrapContentSize(Alignment.TopCenter)
                    .padding(top = overlayOffset)
            ) {
                Box(
                    modifier = Modifier
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.95f),
                            shape = MaterialTheme.shapes.medium
                        )
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val isHighScore = combinationResult in HIGH_SCORE_COMBINATIONS
                        val points = getScore(combinationResult)
                        Text(
                            text = if (isHighScore) "🎉 $combinationResult 🎉 (+$points pts)" else "$combinationResult (+$points pts)",
                            fontSize = 26.sp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )

                        // Show taunt if score is low
                        AnimatedVisibility(
                            visible = showTaunt,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            modifier = Modifier.padding(top = 12.dp)
                        ) {
                            Text(
                                text = currentTaunt,
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }

            // Confetti animation for high scores
            if (confettiTrigger) {
                KonfettiView(
                    modifier = Modifier.fillMaxSize().zIndex(2f),
                    parties = listOf(
                        Party(
                            angle = Angle.TOP,
                            speed = 0f,
                            maxSpeed = 30f,
                            damping = 0.9f,
                            spread = 360,
                            colors = listOf(0xFFF44336.toInt(), 0xFF4CAF50.toInt(), 0xFF2196F3.toInt()),
                            emitter = Emitter(duration = 2, TimeUnit.SECONDS).perSecond(150),
                            position = Position.Relative(0.5, 0.0),
                            shapes = listOf(Shape.Circle, Shape.Square),
                            size = listOf(Size(8), Size(12))
                        )
                    )
                )
            }
        }
    }
}

// Shows a single dice image with rotation applied
@Composable
fun DiceImage(result: Int, size: Dp, rotation: Float) {
    Image(
        painter = painterResource(getDiceImage(result)),
        contentDescription = null,
        modifier = Modifier
            .size(size)
            .graphicsLayer(rotationZ = rotation)
    )
}

// Maps a dice number to its image resource
fun getDiceImage(result: Int): Int {
    return when (result) {
        1 -> R.drawable.dice_1
        2 -> R.drawable.dice_2
        3 -> R.drawable.dice_3
        4 -> R.drawable.dice_4
        5 -> R.drawable.dice_5
        else -> R.drawable.dice_6
    }
}

// Evaluates the combination result from a list of dice
fun evaluateDice(dice: List<Int>): String {
    val counts = dice.groupingBy { it }.eachCount().values.sortedDescending()
    val sortedDice = dice.sorted()
    return when {
        counts == listOf(5) -> "Five of a Kind"
        counts == listOf(4, 1) -> "Four of a Kind"
        counts == listOf(3, 2) -> "Full House"
        counts == listOf(3, 1, 1) -> "Three of a Kind"
        counts == listOf(2, 2, 1) -> "Two Pair"
        counts == listOf(2, 1, 1, 1) -> "One Pair"
        sortedDice == listOf(1, 2, 3, 4, 5) -> "Small Straight"
        sortedDice == listOf(2, 3, 4, 5, 6) -> "Large Straight"
        else -> "No Combination"
    }
}

// Returns score value based on combination
fun getScore(combination: String): Int {
    return when (combination) {
        "Five of a Kind" -> 50
        "Four of a Kind" -> 40
        "Full House" -> 35
        "Large Straight" -> 30
        "Small Straight" -> 25
        "Three of a Kind" -> 20
        "Two Pair" -> 15
        "One Pair" -> 10
        else -> 0
    }
}

@Preview(showBackground = true)
@Composable
fun DiceRollerApp() {
    DiceWithButtonAndImage(
        modifier = Modifier
            .fillMaxSize()
            .wrapContentSize(Alignment.Center)
    )
}
