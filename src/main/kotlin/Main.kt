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

    private val chatIdWaitForName = mutableSetOf<Long>()
    fun setWaitName(chatId: Long) = chatIdWaitForName.add(chatId)
    fun isWaitForName(chatId : Long) = chatIdWaitForName.contains(chatId)
    fun unsetWaitForName(chatId : Long) = chatIdWaitForName.remove(chatId)


    private val statesByUsername = mutableMapOf<String, ActionState>()


    fun getState(userName : String) = statesByUsername.get(userName)?.state ?: State.None

    fun clearUserState(userName : String) = statesByUsername.remove(userName)

    fun setTarget(userName : String, target : String) : Boolean {
        val state = statesByUsername.get(userName) ?: throw MyException("State for $userName not found")
        state.target = target
        return true
    }

    fun trySetCount(userName : String, text : String) : Pair<String, Boolean>  {
        val state = statesByUsername.get(userName) ?: throw MyException("State for $userName not found")

        var isEnd = false

        if(text == "<<") {
            if(state.count.isNotEmpty()) {
                state.count = state.count.substring(0, state.count.length - 1)
            }
        }
        else {
            if(text.toIntOrNull() != null) {
                if(text.length > 1) {
                    state.count = text
                    isEnd = true
                }
                else {
                    state.count = state.count + text
                }
            }
        }

        return Pair(state.count, isEnd)
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

            bot.execute(SendMessage(chatId, "О ком речь?").replyMarkup(replyKeyboard))
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

            bot.execute(SendMessage(chatId, "И сколько?").replyMarkup(replyKeyboard))
        }
        else if(newState == State.WaitComment) {
            val state = statesByUsername.get(userName) ?: throw MyException("Try set state to $newState but state record not found")
            state.state = newState

            val sum = state.count.toIntOrNull() ?: throw MyException("Try set state to $newState but state record not found")

            if(sum <= 0) {
                bot.execute(SendMessage(chatId, "Что я должен сделать с этим числом ${state.count}? нажми /start и всё переделай").replyMarkup(ReplyKeyboardRemove()))
            }
            else {
                bot.execute(SendMessage(chatId, "Число понял: ${state.count}").replyMarkup(ReplyKeyboardRemove()))

                when (state.baseState) {
                    State.PayTo -> {
                        DbManager.doRecord(userName, userName, state.target, sum)
                        val targetChatId = DbManager.getChatId(state.target)
                        if(targetChatId != 0L) {
                            bot.execute(SendMessage(targetChatId, "${userName} сказал что ты должен ему ${state.count} денег"))
                        }
                    }
                    State.PayMe -> {
                        DbManager.doRecord(userName, state.target, userName, sum)
                        val targetChatId = DbManager.getChatId(state.target)
                        if(targetChatId != 0L) {
                            bot.execute(SendMessage(targetChatId, "${userName} сказал что должен тебе ${state.count} денег"))
                        }
                    }
                }
                bot.execute(SendMessage(chatId, "Как записать?\nесли никак - жми /start"))
            }
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
        Action("Я кому-то должен", { chatId, userName, bot ->
            UserState.setState(chatId, userName, bot, UserState.State.PayMe)
        }),
        Action("Кто-то должен мне", { chatId, userName, bot ->
            UserState.setState(chatId, userName, bot, UserState.State.PayTo)
        }),
        Action("Баланс", { chatId, userName, bot ->
            bot.execute(SendMessage(chatId, "Твой счет: ${DbManager.getBalance(userName)}"))
        }),
        Action("История", { chatId, userName, bot ->

            val opListTmp = DbManager.getOperationList(userName)
            val maxSumLength = opListTmp.maxByOrNull { it.sum.toString().length }?.sum.toString().length
            val maxNameLength = opListTmp.maxByOrNull {it.target.length}?.target.toString().length

            val operationList = DbManager.getOperationList(userName).map {
                var sumStr = if(it.notAMaster) "-" + it.sum else "+" + it.sum
                sumStr = sumStr.padEnd(maxSumLength + 1,' ')

                var targetStr = it.target.padEnd(maxNameLength,' ')

                "${it.operationDate}  ${sumStr}  ${targetStr}  ${it.comment}"
            }.joinToString("\n")
            bot.execute(SendMessage(chatId, "<pre>${operationList}</pre>").parseMode(ParseMode.HTML))


            /*
            val operationList = DbManager.getOperationList(userName).map {
                "${it.operationDate}    ${if(it.notAMaster) "-" else "+"}${it.sum.toString().padStart(4,'\t')}    ${it.target}    ${it.comment}"
            }.joinToString("\n")
            bot.execute(SendMessage(chatId, "<pre>История:\n${operationList}</pre>").parseMode(ParseMode.HTML))*/
            /*
            val operationList = DbManager.getOperationList(userName).map {
                "${it.operationDate}   ${it.target}  ${if(it.notAMaster) "-" else "+"}${it.sum}         ${it.comment}"
            }.joinToString("\n")
            bot.execute(SendMessage(chatId, "История:\n${operationList}"))*/
        })
    )

    fun tryHandleMessage(text : String, chatId : Long, userName : String, bot : TelegramBot) {
        if (actions.find { it.name == text }?.action?.invoke(chatId, userName, bot) == null) {
            throw MyException("User $userName unknown main menu message")
        }
    }

    fun sendToUser(chatId : Long, bot : TelegramBot) {
        val replyKeyboard = KotlinReplyKeyboardMarkup(
            arrayOf(
                arrayOf(actions[0].name, actions[1].name),
                arrayOf(actions[2].name, actions[3].name)
            )
        )
        bot.execute(SendMessage(chatId, "Какие проблемы?").replyMarkup(replyKeyboard))
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
            //val userName = chat?.username() ?: return@forEach
            val text = it?.message()?.text() ?: return@forEach

            try {
                if(text == "/name") {
                    UserState.setWaitName(chatId)
                    bot.execute(SendMessage(chatId, "Как тебя зовут?"))
                }
                else if (text == "/start") {
                    val userName = DbManager.getNameByChatId(chatId)
                    if(userName == null) {
                        UserState.setWaitName(chatId)
                        bot.execute(SendMessage(chatId, "Как тебя зовут?"))
                    }
                    else {
                        UserState.clearUserState(userName)
                        UserState.unsetWaitForName(chatId)
                        MainMenu.sendToUser(chatId, bot)
                    }
                }
                else if(UserState.isWaitForName(chatId)) {
                    val name = text
                    if(DbManager.isNameExists(name)) {
                        bot.execute(SendMessage(chatId, "Такое имя уже существует. Давай еще раз"))

                        // send name already exists
                           // retry
                           //if(DbManager.tryAddUser(name, chatId))
                    }
                    else {
                        UserState.unsetWaitForName(chatId)
                        if(DbManager.tryAddOrUpdateUser(name, chatId)) {
                            bot.execute(SendMessage(chatId, "Ты добавлен. тебя зовут ${name}"))
                            MainMenu.sendToUser(chatId, bot)
                        }
                    }
                }
                else {
                    val userName = DbManager.getNameByChatId(chatId) ?: return@forEach

                    val userState = UserState.getState(userName)
                    if (userState == UserState.State.None) {
                        MainMenu.tryHandleMessage(text, chatId, userName, bot)
                    } else if (userState == UserState.State.PayTo || userState == UserState.State.PayMe) {
                        if (!DbManager.checkUserExists(text)) {
                            throw MyException("User $userName state $userState unknown target $text")
                        }
                        UserState.setTarget(userName, text)
                        UserState.setState(chatId, userName, bot, UserState.State.WaitCount)
                    } else if (userState == UserState.State.WaitCount) {
                        if (text == "OK") {
                            UserState.setState(chatId, userName, bot, UserState.State.WaitComment)
                        } else {
                            val count = UserState.trySetCount(userName, text)
                            if (count.second) {
                                UserState.setState(chatId, userName, bot, UserState.State.WaitComment)
                            } else {
                                bot.execute(SendMessage(chatId, count.first))
                            }
                        }
                    } else if (userState == UserState.State.WaitComment) {
                        if (text.isNotEmpty()) {
                            DbManager.addComment(userName, text)
                            UserState.clearUserState(userName)
                            MainMenu.sendToUser(chatId, bot)
                        }
                    }
                }
            }
            catch(exception : MyException) {
                val userName = DbManager.getNameByChatId(chatId)
                if(userName != null) {
                    UserState.clearUserState(userName)
                }
                MainMenu.sendToUser(chatId, bot)
            }
        }

        UpdatesListener.CONFIRMED_UPDATES_ALL
    }

}
