package DuckyLuckTgBot

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime

object Users : Table() {
    val id = integer("id").autoIncrement().primaryKey()
    val name = varchar("name", length = 100).uniqueIndex()
    val balance = integer("balance").default(0)
}

object Logs : Table() {
    val id = integer("id").autoIncrement().primaryKey()
    val operator = integer("operator")
    val master = integer("master")
    val slave = integer("slave")
    val sum = integer("value")
    val operationTime = datetime("operation_time")
    val commentMessage = varchar("comment", length = 100).default("")
}

object DbManager {
    fun Init() {
        connect()
        transaction {
            SchemaUtils.createMissingTablesAndColumns(Users, Logs)
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

    fun tryAddUser(userName: String) : Boolean {
        connect()
        var ret = false
        transaction {
            if (Users.select({ Users.name eq userName }).empty()) {
                Users.insert {
                    it[Users.name] = userName
                    it[Users.balance] = 0
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

            Logs.insert {
                it[Logs.operator] = operatorId
                it[Logs.master] = masterId
                it[Logs.slave] = slaveId
                it[Logs.sum] = sum
                it[Logs.operationTime] = DateTime.now()
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

    data class LogOperation(
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
                .slice(Logs.id, masterNameTable[Users.name], slaveNameTable[Users.name], Logs.sum, Logs.commentMessage)
                .select({ Logs.master eq operatorId or (Logs.slave eq operatorId)})
                .orderBy(Logs.id to SortOrder.DESC)
                .limit(Config[Config.log_limit])
                .map { LogOperation(
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
