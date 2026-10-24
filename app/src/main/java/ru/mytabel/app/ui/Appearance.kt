package ru.mytabel.app.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import ru.mytabel.app.settings.AppSettings
import ru.mytabel.app.settings.translate

val LocalAppSettings=staticCompositionLocalOf{AppSettings()}
fun paletteColor(palette:String)=when(palette){"PURPLE"->0xFF7C3AED;"GREEN"->0xFF00855A;"ORANGE"->0xFFD44A15;else->0xFF1464F4}
@Composable fun appColorScheme(color:Long,night:Boolean):ColorScheme {
    val primary=Color(color);val tint=primary.copy(alpha=if(night)0.32f else 0.12f)
    val base=if(night)Color(0xFF10151E)else Color(0xFFFAFBFF)
    val container=tint.compositeOver(base)
    return if(night)darkColorScheme(primary=primary,onPrimary=Color.White,primaryContainer=container,onPrimaryContainer=Color(0xFFF1F4FF),secondary=primary,secondaryContainer=container,onSecondaryContainer=Color.White,
        background=base,surface=base,surfaceContainer=Color(0xFF1B2330),surfaceContainerLow=Color(0xFF161D29),onSurface=Color(0xFFEEF2FC),onSurfaceVariant=Color(0xFFBFCADD))
    else lightColorScheme(primary=primary,onPrimary=Color.White,primaryContainer=container,onPrimaryContainer=Color(0xFF122341),secondary=primary,secondaryContainer=container,onSecondaryContainer=Color(0xFF122341),
        background=base,surface=base,surfaceContainer=Color(0xFFEEF2FA),surfaceContainerLow=Color.White,onSurface=Color(0xFF182238),onSurfaceVariant=Color(0xFF536079))
}

@Composable fun AppText(text:String,modifier:Modifier=Modifier,color:Color=Color.Unspecified,fontWeight:FontWeight?=null,style:TextStyle=LocalTextStyle.current,maxLines:Int=Int.MAX_VALUE,textAlign:TextAlign?=null){
    val settings=LocalAppSettings.current
    Text(translate(text,settings.language).replace("$/",settings.currency+"/").replace(", $",", "+settings.currency),modifier=modifier,color=color,fontWeight=fontWeight,style=style,maxLines=maxLines,textAlign=textAlign)
}
