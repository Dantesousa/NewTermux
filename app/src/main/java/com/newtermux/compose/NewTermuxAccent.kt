package com.newtermux.compose

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.newtermux.features.NewTermuxTheme

/**
 * Acento (cor de destaque) do app, compartilhado entre as telas Compose.
 *
 * O valor vem do Termux (NewTermuxTheme), mas fica num estado observavel
 * para que trocar a cor no painel do Pi reflita na hora em todas as telas,
 * sem precisar reiniciar o app.
 */
object NewTermuxAccent {

    /** 0 = ainda nao carregado (o tema usa a cor das preferencias). */
    var color by mutableIntStateOf(0)
        private set

    /** Le a cor salva e publica no estado. */
    fun load(context: Context) {
        color = NewTermuxTheme.getAccentColor(context)
    }

    /** Troca a cor (persiste nas preferencias do Termux) e publica. */
    fun set(context: Context, value: Int) {
        NewTermuxTheme.setAccentColor(context, value)
        color = value
    }
}
