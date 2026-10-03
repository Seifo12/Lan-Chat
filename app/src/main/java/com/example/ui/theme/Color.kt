package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Primary Palette: Modern Royal Blue & Sapphire Indigo (Distinctive, P2P Tech aesthetic)
val PrimaryBrand = Color(0xFF2563EB)
val PrimaryBrandDark = Color(0xFF1D4ED8)
val PrimaryBrandLight = Color(0xFF3B82F6)
val PrimaryBrandContainer = Color(0xFFEFF6FF)
val OnPrimaryBrandContainer = Color(0xFF1E3A8A)

// Compatibility alias remapped to PrimaryBrand
val PrimaryGreen = PrimaryBrand
val PrimaryGreenDark = PrimaryBrandDark
val PrimaryGreenLight = PrimaryBrandLight
val PrimaryGreenContainer = PrimaryBrandContainer

// Neutral & Background Canvas
val CanvasBackground = Color(0xFFF8FAFC)
val SurfaceWhite = Color(0xFFFFFFFF)
val SurfaceCardActive = Color(0xFFF1F5F9)
val BorderLight = Color(0xFFE2E8F0)
val BorderFocused = Color(0xFF2563EB)

// High-Contrast Text for Accessibility & Clarity
val TextPrimary = Color(0xFF0F172A)
val TextSecondary = Color(0xFF475569)
val TextDisabled = Color(0xFF94A3B8)

// Chat Bubbles - Unique Brand Style
val BubbleSender = Color(0xFF2563EB) // Vibrant Royal Blue outgoing bubble
val BubbleSenderBorder = Color(0xFF1D4ED8)
val BubbleReceiver = Color(0xFFF1F5F9) // Clean Slate-100 incoming bubble
val BubbleReceiverBorder = Color(0xFFE2E8F0)

// Online Status & Receipts
val OnlineGreen = Color(0xFF10B981)
val OfflineGray = Color(0xFF94A3B8)
val ReceiptSentGray = Color(0xFF94A3B8)
val ReceiptDeliveredGray = Color(0xFF64748B)
val ReceiptReadBlue = Color(0xFF38BDF8)

// Avatar Pastel Monogram Palettes
val AvatarColors = listOf(
    Pair(Color(0xFFD7E8CD), Color(0xFF002106)), // Green tint
    Pair(Color(0xFFE8E0EB), Color(0xFF26192B)), // Lavender tint
    Pair(Color(0xFFFFE0B2), Color(0xFFE65100)), // Warm Orange tint
    Pair(Color(0xFFB3E5FC), Color(0xFF01579B)), // Sky Blue tint
    Pair(Color(0xFFF8BBD0), Color(0xFF880E4F)), // Rose tint
    Pair(Color(0xFFFFF9C4), Color(0xFFF57F17)), // Sunny Yellow tint
    Pair(Color(0xFFD1C4E9), Color(0xFF311B92)), // Deep Purple tint
    Pair(Color(0xFFB2DFDB), Color(0xFF004D40))  // Teal tint
)
