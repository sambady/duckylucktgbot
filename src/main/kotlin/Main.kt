package DuckyLuckTgBot


import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.UpdatesListener
import com.pengrad.telegrambot.model.request.*
import com.pengrad.telegrambot.request.SendMessage
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy


class MyException(message : String) : Exception(message)

object UserState {
    enum class State {
        None,
        PayTo,
        PayMe,
        WaitCount,
        WaitComment
    }

    class ActionState (
        var state : State,
        var baseState : State,
        var target : String,
        var count : String
    )

    private val statesByUsername = mutableMapOf<String, ActionState>()

    fun getState(userName : String) = statesByUsername.get(userName)?.state ?: State.None

    fun clearUserState(userName : String) = statesByUsername.remove(userName)

    fun setTarget(userName : String, target : String) : Boolean {
        val state = statesByUsername.get(userName) ?: throw MyException("State for $userName not found")
        state.target = target
        return true
    }

    fun trySetCount(userName : String, text : String) : String  {
        val state = statesByUsername.get(userName) ?: throw MyException("State for $userName not found")

        if(text == "<<") {
            if(state.count.isNotEmpty()) {
                state.count = state.count.substring(0, state.count.length - 1)
            }
        }
        else {
            if(text.toIntOrNull() != null) {
                state.count = state.count + text
            }
        }

        return state.count
    }

    fun setState(chatId : Long, userName : String, bot : TelegramBot, newState : State) {

        if(newState == State.PayMe || newState == State.PayTo) {

            statesByUsername[userName] = ActionState(newState, newState,"", "")

            val users = DbManager.getUsers().filter{it != userName}

            val replyKeyboard = KotlinReplyKeyboardMarkup(
                users.chunked(2).map {it.toTypedArray()}.toTypedArray(),
                isResizeKeyboard = true,
                isSelective = true
            )

            bot.execute(SendMessage(chatId, "Select target:").replyMarkup(replyKeyboard))
        }
        else if(newState == State.WaitCount) {
            val state = statesByUsername.get(userName) ?: throw MyException("Try set state to $newState but state record not found")
            state.state = newState

            val replyKeyboard = KotlinReplyKeyboardMarkup(
                arrayOf(
                    arrayOf("1", "2", "3"),
                    arrayOf("4", "5", "6"),
                    arrayOf("7", "8", "9"),
                    arrayOf("<<", "0", "OK")
                )
            )

            bot.execute(SendMessage(chatId, "Please enter count:").replyMarkup(replyKeyboard))
        }
        else if(newState == State.WaitComment) {
            val state = statesByUsername.get(userName) ?: throw MyException("Try set state to $newState but state record not found")
            state.state = newState

            val sum = state.count.toIntOrNull() ?: throw MyException("Try set state to $newState but state record not found")

            bot.execute(SendMessage(chatId, "Enter sum end: ${state.count}").replyMarkup(ReplyKeyboardRemove()))

            when (state.baseState) {
                State.PayTo -> DbManager.doRecord(userName, userName, state.target, sum)
                State.PayMe -> DbManager.doRecord(userName, state.target, userName, sum)
            }
            bot.execute(SendMessage(chatId, "Please send comment:\n or press /start to enter main menu"))
        }
    }
}


object MainMenu
{
    class Action (
        val name : String,
        val action : (chatId : Long, userName : String, bot : TelegramBot) -> Unit
    )

    private val actions = listOf<Action> (
        Action("I pay", { chatId, userName, bot ->
            UserState.setState(chatId, userName, bot, UserState.State.PayTo)
        }),
        Action("Pay to me", { chatId, userName, bot ->
            UserState.setState(chatId, userName, bot, UserState.State.PayMe)
        }),
        Action("My status", { chatId, userName, bot ->
            bot.execute(SendMessage(chatId, "Balance ${DbManager.getBalance(userName)}"))
        }),
        Action("My log", { chatId, userName, bot ->
            val operationList = DbManager.getOperationList(userName).map {
                "${if(it.notAMaster) "+" else "-"} ${it.target} - ${it.sum}"
            }
            bot.execute(SendMessage(chatId, "Log:\n${operationList}"))
        })
    )

    fun tryHandleMessage(text : String, chatId : Long, userName : String, bot : TelegramBot) {
        if (actions.find { it.name == text }?.action?.invoke(chatId, userName, bot) == null) {
            throw MyException("User $userName unknown main menu message")
        }
    }

    fun sendToUser(chatId : Long, userName : String, bot : TelegramBot) {
        val replyKeyboard = KotlinReplyKeyboardMarkup(
            arrayOf(
                arrayOf(actions[0].name, actions[1].name),
                arrayOf(actions[2].name, actions[3].name)
            )
        )

        bot.execute(SendMessage(chatId, "What do you want?").replyMarkup(replyKeyboard))
    }
}

fun main(args: Array<String>) {

    if(args.isEmpty()) {
        println("First arg is config file");
        return;
    }

    Config.Init(args[0])
    Config.print()

    val client = OkHttpClient.Builder().build()

    //val client =
    //        OkHttpClient.Builder().proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("77.94.144.164", 3128))).build()
    val bot = TelegramBot.Builder(Config[Config.bot_key]).okHttpClient(client).build()

    DbManager.Init()

    bot.setUpdatesListener { updates ->
        // ... process updates
        // return id of last processed update or confirm them all

        updates.forEach {
            val chat = it?.callbackQuery()?.message()?.chat() ?: it?.message()?.chat()

            val chatId = chat?.id() ?: return@forEach
            val userName = chat?.username() ?: return@forEach
            val text = it?.message()?.text() ?: return@forEach

            try {
                val userState = UserState.getState(userName)
                if (text == "/start") {
                    DbManager.tryAddUser(userName)
                    UserState.clearUserState(userName)
                    MainMenu.sendToUser(chatId, userName, bot)
                } else if (userState == UserState.State.None) {
                    MainMenu.tryHandleMessage(text, chatId, userName, bot)
                } else if (userState == UserState.State.PayTo || userState == UserState.State.PayMe) {
                    if (!DbManager.checkUserExists(text)) {
                        throw MyException("User $userName state $userState unknown target $text")
                    }
                    UserState.setTarget(userName, text)
                    UserState.setState(chatId, userName, bot, UserState.State.WaitCount)
                }
                else if (userState == UserState.State.WaitCount) {
                    if (text == "OK") {
                        UserState.setState(chatId, userName, bot, UserState.State.WaitComment)
                    }
                    else {
                        val count = UserState.trySetCount(userName, text)
                        bot.execute(SendMessage(chatId, count))
                    }
                } else if (userState == UserState.State.WaitComment) {
                    if (text.isNotEmpty()) {
                        DbManager.addComment(userName, text)
                        UserState.clearUserState(userName)
                        MainMenu.sendToUser(chatId, userName, bot)
                    }
                }
            }
            catch(exception : MyException) {
                UserState.clearUserState(userName)
                MainMenu.sendToUser(chatId, userName, bot)
            }
        }

        UpdatesListener.CONFIRMED_UPDATES_ALL
    }

}
