package DuckyLuckTgBot

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.format.DateTimeFormatter



object Users : Table() {
    val id = integer("id").autoIncrement()
    val name = varchar("name", length = 100).uniqueIndex()
    val balance = integer("balance").default(0)
    val chatId = long("chat_id").default(0).uniqueIndex()
}

object UsersRelations : Table("users_relations") {
    val source_user_id = integer("source_user_id")
    val target_user_id = integer("target_user_id")
    val balance = integer("balance")

    override val primaryKey = PrimaryKey(source_user_id, target_user_id)
}

object Logs : Table() {
    val id = integer("id").autoIncrement()
    val operator = integer("operator")
    val master = integer("master")
    val slave = integer("slave")
    val sum = integer("value")
    val operationTime = datetime("operation_time").defaultExpression(CurrentDateTime)
    val commentMessage = varchar("comment", length = 100).default("")
}

object DbManager {
    fun Init() {
        connect()
        transaction {
            SchemaUtils.createMissingTablesAndColumns(Users, Logs, UsersRelations)
        }
    }

    fun getUsers(): List<String> {
        connect()
        var ret = listOf<String>()
        transaction {
            ret = Users.selectAll().map { it[Users.name] }
        }
        return ret
    }

    fun getNameByChatId(chatId : Long) : String?
    {
        connect()
        var ret : String? = null
        transaction {
            ret = Users.select({ Users.chatId eq chatId })
                .map { it[Users.name].toString() }
                .getOrNull(0)
                ?: return@transaction
        }
        return ret
    }

    fun isUserExists(chatId: Long) : Boolean
    {
        connect()
        var ret = false
        transaction {
            if (Users.select { Users.chatId eq chatId }.empty()) {
                ret = true
            }
        }
        return ret
    }

    fun isNameExists(name : String) : Boolean
    {
        connect()
        var ret = false
        transaction {
            ret = Users.select { Users.name eq name }.empty().not()
        }
        return ret
    }

    fun tryAddOrUpdateUser(userName: String, chatId : Long) : Boolean {
        connect()
        var ret = false
        transaction {
            val chatIdExists = Users.select{Users.chatId eq chatId}.empty().not()
            val userNameExists = Users.select{Users.name eq userName}.empty().not()
            if(userNameExists)
                return@transaction

            if(chatIdExists) {
                Users.update({ Users.chatId eq chatId }) {
                    it[Users.name] = userName
                }
                ret = true
            }
            else {
                Users.insert {
                    it[Users.name] = userName
                    it[Users.balance] = 0
                    it[Users.chatId] = chatId
                }
                ret = true
            }
        }
        return ret
    }

    fun checkUserExists(userName: String) : Boolean {
        connect()
        var ret = false
        transaction {
            ret = Users.select({ Users.name eq userName }).empty().not()
        }
        return ret
    }

    fun getChatId(userName : String) : Long {
        connect()
        var chatId : Long = 0
        transaction {
            chatId = Users.select({ Users.name eq userName })
                .map { it[Users.chatId].toLong() }
                .getOrNull(0)
                ?: return@transaction
        }
        return chatId
    }

    fun updateUserRelations(source_user_id : Int, target_user_id : Int, balanceChange : Int)
    {
        if(UsersRelations
                .select {
                    (UsersRelations.source_user_id eq source_user_id) and (UsersRelations.target_user_id eq target_user_id)
                }
                .empty())
        {
            UsersRelations.insert {
                it[UsersRelations.source_user_id] = source_user_id
                it[UsersRelations.target_user_id] = target_user_id
                it[balance] = balanceChange
            }
        }
        else {
            UsersRelations.update({
                (UsersRelations.source_user_id eq source_user_id) and (UsersRelations.target_user_id eq target_user_id)
            }
            ) {
                with(SqlExpressionBuilder) {
                    it.update(UsersRelations.balance, UsersRelations.balance + balanceChange)
                }
            }
        }
    }

    fun doRecord(operator : String, master: String, slave: String, sum: Int) {
        connect()
        transaction {
            val operatorId = Users.select({ Users.name eq operator })
                .map { it[Users.id].toInt() }
                .getOrNull(0)
                ?: return@transaction
            val masterId = Users.select({ Users.name eq master })
                .map { it[Users.id].toInt() }
                .getOrNull(0)
                ?: return@transaction
            val slaveId = Users.select({ Users.name eq slave })
                .map { it[Users.id].toInt() }
                .getOrNull(0)
                ?: return@transaction

            Users.update({ Users.id eq masterId }) {
                with(SqlExpressionBuilder) {
                    it.update(Users.balance, Users.balance + sum)
                }
            }

            Users.update({ Users.id eq slaveId }) {
                with(SqlExpressionBuilder) {
                    it.update(Users.balance, Users.balance - sum)
                }
            }

            updateUserRelations(masterId, slaveId, sum)
            updateUserRelations(slaveId, masterId, -sum)

            Logs.insert {
                it[Logs.operator] = operatorId
                it[Logs.master] = masterId
                it[Logs.slave] = slaveId
                it[Logs.sum] = sum
            }
        }
    }

    fun addComment(userName: String, commentString: String) : String {
        connect()
        var ret = ""
        transaction {
            val operatorId = Users.select({ Users.name eq userName })
                .map { it[Users.id].toInt() }
                .getOrNull(0)
                ?: return@transaction

            val masterNameTable = Users.alias("master_name")
            val slaveNameTable = Users.alias("slave_name")

            val lastLog = Logs
                .innerJoin(masterNameTable, {Logs.master}, { masterNameTable[Users.id] })
                .innerJoin(slaveNameTable, {Logs.slave}, { slaveNameTable[Users.id] })
                .slice(Logs.id, masterNameTable[Users.name], slaveNameTable[Users.name], Logs.sum, Logs.commentMessage)
                .select({ Logs.operator eq operatorId })
                .orderBy(Logs.id to SortOrder.DESC)
                .limit(1).map{ it }.firstOrNull() ?: return@transaction

            ret = "${lastLog[masterNameTable[Users.name]]} -> ${lastLog[slaveNameTable[Users.name]]} : ${lastLog[Logs.sum]} - $commentString"

            Logs.update({ Logs.id eq lastLog[Logs.id] }) {
                it[Logs.commentMessage] = String(commentString.toByteArray(), Charsets.UTF_8)
            }
        }
        return ret
    }

    fun getBalance(userName: String): Int {
        connect()
        var balance = 0
        transaction {
            balance = Users.select({ Users.name eq userName })
                .map { it[Users.balance].toInt() }
                .getOrNull(0)
                ?: 0
        }
        return balance
    }

    fun getBalanceExt(userName : String) : Map<String, Int>? {
        connect()

        var ret : Map<String, Int>? = null
        transaction {
            val userId = Users.select({Users.name eq userName}).map {it[Users.id]}.getOrNull(0)
            if(userId != null) {
                ret = UsersRelations.leftJoin(Users, {UsersRelations.target_user_id}, {Users.id})
                    .slice(Users.name, UsersRelations.balance)
                    .select({ UsersRelations.source_user_id eq userId })
                    .map { Pair(it[Users.name], it[UsersRelations.balance])}.toMap()
            }
        }
        return ret
    }

    data class LogOperation(
        val operationDate : String,
        val notAMaster : Boolean,
        val target : String,
        val sum : Int,
        val comment : String
    )

    fun getOperationList(userName: String): List<LogOperation> {
        connect()
        var operations = mutableListOf<LogOperation>()
        transaction {
            val operatorId = Users.select({ Users.name eq userName })
                .map { it[Users.id].toInt() }
                .getOrNull(0)
                ?: return@transaction

            val masterNameTable = Users.alias("master_name")
            val slaveNameTable = Users.alias("slave_name")

            operations = Logs
                .innerJoin(masterNameTable, {Logs.master}, { masterNameTable[Users.id] })
                .innerJoin(slaveNameTable, {Logs.slave}, { slaveNameTable[Users.id] })
                .slice(Logs.operationTime, Logs.id, masterNameTable[Users.name], slaveNameTable[Users.name], Logs.sum, Logs.commentMessage)
                .select({ Logs.master eq operatorId or (Logs.slave eq operatorId)})
                .orderBy(Logs.id to SortOrder.DESC)
                .limit(Config[Config.log_limit])
                .sortedBy { it[Logs.id] }
                .map { LogOperation(
                    operationDate = it[Logs.operationTime].format(DateTimeFormatter.ofPattern("dd.MM HH:mm")),
                    notAMaster = (userName != it[masterNameTable[Users.name]]),
                    target =    if(userName == it[masterNameTable[Users.name]]) {
                        it[slaveNameTable[Users.name]]
                    }
                    else {
                        it[masterNameTable[Users.name]]
                    },
                    sum = it[Logs.sum],
                    comment = it[Logs.commentMessage]
                )}
                //.map {"${it[masterNameTable[Users.name]]} -> ${it[slaveNameTable[Users.name]]} ${it[Logs.sum]} - ${it[Logs.commentMessage]} ".toString() }
                .toMutableList()
        }
        return operations
    }

    private fun connect() {
        Database.connect(url = Config[Config.db_url],
            driver =  Config[Config.db_driver],
            user = Config[Config.db_username],
            password = Config[Config.db_password])
    }
}
