package DuckyLuckTgBot

import com.pengrad.telegrambot.model.request.Keyboard
import com.pengrad.telegrambot.model.request.KeyboardButton

class KotlinReplyKeyboardMarkup : Keyboard {

    constructor(buttons : Array<Array<String>>,
                isResizeKeyboard : Boolean = false,
                isOneTimeKeyboard : Boolean = false,
                isSelective : Boolean = false)
    {
        keyboard = buttons.map { it.map{ KeyboardButton(it) }.toTypedArray()}.toTypedArray()
        resize_keyboard = isResizeKeyboard
        one_time_keyboard = isOneTimeKeyboard
        selective = isSelective
    }

    private val keyboard: Array<Array<KeyboardButton>>
    private val resize_keyboard: Boolean
    private val one_time_keyboard: Boolean
    private val selective: Boolean
}