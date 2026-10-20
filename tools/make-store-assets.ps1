<#
.SYNOPSIS
    Draws the two graphics Google Play requires: the 512x512 icon and the
    1024x500 feature graphic.

.DESCRIPTION
    Uses GDI+, which ships with Windows, so nothing has to be installed.
    Output goes to store\ at the project root.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $PSScriptRoot
$outDir = Join-Path $root "store"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

function New-RoundedPath {
    param([float]$X, [float]$Y, [float]$W, [float]$H, [float]$R)
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $R * 2
    $path.AddArc($X,           $Y,           $d, $d, 180, 90)
    $path.AddArc($X + $W - $d, $Y,           $d, $d, 270, 90)
    $path.AddArc($X + $W - $d, $Y + $H - $d, $d, $d,   0, 90)
    $path.AddArc($X,           $Y + $H - $d, $d, $d,  90, 90)
    $path.CloseFigure()
    return $path
}

function New-Gradient {
    param([float]$X, [float]$Y, [float]$W, [float]$H, [string]$From, [string]$To, [float]$Angle = 60)
    $rect = New-Object System.Drawing.RectangleF $X, $Y, $W, $H
    return New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        $rect,
        [System.Drawing.ColorTranslator]::FromHtml($From),
        [System.Drawing.ColorTranslator]::FromHtml($To),
        $Angle
    )
}

function New-Canvas {
    param([int]$Width, [int]$Height)
    $bmp = New-Object System.Drawing.Bitmap $Width, $Height,
        ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode     = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    return @{ Bitmap = $bmp; Graphics = $g }
}

# --- the mark: a stack of photo frames with a sunrise on the front one -------

function Draw-Mark {
    param($G, [float]$CX, [float]$CY, [float]$Size)

    $w = $Size * 0.74
    $h = $Size
    $r = $Size * 0.11

    # Two cards behind, fanned out.
    foreach ($offset in @(@{ dx = $Size * 0.30; a = 55 }, @{ dx = $Size * 0.16; a = 110 })) {
        $path = New-RoundedPath ($CX - $w / 2 + $offset.dx) ($CY - $h / 2 + $Size * 0.055) $w ($h * 0.89) $r
        $brush = New-Object System.Drawing.SolidBrush(
            [System.Drawing.Color]::FromArgb($offset.a, 143, 184, 255))
        $G.FillPath($brush, $path)
        $brush.Dispose(); $path.Dispose()
    }

    # The front card.
    $x = $CX - $w / 2 - $Size * 0.10
    $y = $CY - $h / 2
    $front = New-RoundedPath $x $y $w $h $r
    $brush = New-Gradient $x $y $w $h "#5C97FF" "#2E5FD0" 70
    $G.FillPath($brush, $front)
    $brush.Dispose()

    # Clip the scenery to the card so nothing spills over the rounded corners.
    $saved = $G.Save()
    $G.SetClip($front)

    $sun = New-Object System.Drawing.SolidBrush(
        [System.Drawing.ColorTranslator]::FromHtml("#FFD166"))
    $sunD = $Size * 0.20
    $G.FillEllipse($sun, ($x + $w * 0.20), ($y + $h * 0.18), $sunD, $sunD)
    $sun.Dispose()

    # Two overlapping hills.
    $hillBack = New-Object System.Drawing.Drawing2D.GraphicsPath
    $hillBack.AddPolygon(@(
        (New-Object System.Drawing.PointF ($x),               ($y + $h * 0.80)),
        (New-Object System.Drawing.PointF ($x + $w * 0.38),   ($y + $h * 0.46)),
        (New-Object System.Drawing.PointF ($x + $w * 0.78),   ($y + $h * 0.80)),
        (New-Object System.Drawing.PointF ($x),               ($y + $h * 0.80))
    ))
    $b1 = New-Object System.Drawing.SolidBrush(
        [System.Drawing.Color]::FromArgb(235, 18, 36, 70))
    $G.FillPath($b1, $hillBack); $b1.Dispose(); $hillBack.Dispose()

    $hillFront = New-Object System.Drawing.Drawing2D.GraphicsPath
    $hillFront.AddPolygon(@(
        (New-Object System.Drawing.PointF ($x + $w * 0.26), ($y + $h)),
        (New-Object System.Drawing.PointF ($x + $w * 0.68), ($y + $h * 0.58)),
        (New-Object System.Drawing.PointF ($x + $w * 1.05), ($y + $h)),
        (New-Object System.Drawing.PointF ($x + $w * 0.26), ($y + $h))
    ))
    $b2 = New-Object System.Drawing.SolidBrush(
        [System.Drawing.ColorTranslator]::FromHtml("#0E1116"))
    $G.FillPath($b2, $hillFront); $b2.Dispose(); $hillFront.Dispose()

    $G.Restore($saved)
    $front.Dispose()
}

# --- 512 x 512 app icon -------------------------------------------------------

$icon = New-Canvas 512 512
$bg = New-Gradient 0 0 512 512 "#131A26" "#060810" 65
$icon.Graphics.FillRectangle($bg, 0, 0, 512, 512)
$bg.Dispose()

Draw-Mark $icon.Graphics 256 252 250

$iconPath = Join-Path $outDir "icon-512.png"
$icon.Bitmap.Save($iconPath, [System.Drawing.Imaging.ImageFormat]::Png)
$icon.Graphics.Dispose(); $icon.Bitmap.Dispose()
Write-Host "Wrote $iconPath" -ForegroundColor Green

# --- 1024 x 500 feature graphic -----------------------------------------------

$feat = New-Canvas 1024 500
$bgF = New-Gradient 0 0 1024 500 "#16203A" "#070B13" 20
$feat.Graphics.FillRectangle($bgF, 0, 0, 1024, 500)
$bgF.Dispose()

# A soft glow behind the mark.
$glow = New-Object System.Drawing.Drawing2D.GraphicsPath
$glow.AddEllipse(560, 40, 520, 440)
$glowBrush = New-Object System.Drawing.Drawing2D.PathGradientBrush($glow)
$glowBrush.CenterColor = [System.Drawing.Color]::FromArgb(70, 92, 151, 255)
$glowBrush.SurroundColors = @([System.Drawing.Color]::FromArgb(0, 92, 151, 255))
$feat.Graphics.FillPath($glowBrush, $glow)
$glowBrush.Dispose(); $glow.Dispose()

Draw-Mark $feat.Graphics 800 250 300

# Leelawadee UI ships with Windows and renders Thai correctly.
$titleFont = New-Object System.Drawing.Font("Leelawadee UI", 58, [System.Drawing.FontStyle]::Bold)
$bodyFont  = New-Object System.Drawing.Font("Leelawadee UI", 26, [System.Drawing.FontStyle]::Regular)
$white = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::White)
$muted = New-Object System.Drawing.SolidBrush (
    [System.Drawing.ColorTranslator]::FromHtml("#9FB3D1"))
$accent = New-Object System.Drawing.SolidBrush (
    [System.Drawing.ColorTranslator]::FromHtml("#8FB8FF"))

$feat.Graphics.DrawString("WallFlow 4K", $titleFont, $white, 72, 150)
$feat.Graphics.DrawString("วอลเปเปอร์รูปและวิดีโอไม่จำกัด", $bodyFont, $muted, 78, 248)
$feat.Graphics.DrawString("นาฬิกาปรับสีตามภาพ · ไม่กินพื้นที่", $bodyFont, $accent, 78, 292)

$titleFont.Dispose(); $bodyFont.Dispose()
$white.Dispose(); $muted.Dispose(); $accent.Dispose()

$featPath = Join-Path $outDir "feature-1024x500.png"
$feat.Bitmap.Save($featPath, [System.Drawing.Imaging.ImageFormat]::Png)
$feat.Graphics.Dispose(); $feat.Bitmap.Dispose()
Write-Host "Wrote $featPath" -ForegroundColor Green

Write-Host ""
Write-Host "Upload these in Play Console under Grow > Main store listing." -ForegroundColor Cyan
Write-Host "Screenshots still have to come from a real device."
