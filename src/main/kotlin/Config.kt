package DuckyLuckTgBot

import com.natpryce.konfig.*
import java.io.File

object Config : Configuration {
    override fun <T> getOrNull(key: Key<T>): T? = config.getOrNull(key)
    override fun list(): List<Pair<Location, Map<String, String>>> = config.list()
    override fun locationOf(key: Key<*>): PropertyLocation? = config.locationOf(key)
    override fun searchPath(key: Key<*>): List<PropertyLocation> = config.searchPath(key)

    lateinit var config : Configuration

    val bot_key = Key("bot.key", stringType)
    val db_url = Key("db.url", stringType)
    val db_driver = Key("db.driver", stringType)
    val db_username = Key("db.username", stringType)
    val db_password = Key("db.password", stringType)

    fun Init(fileName : String) {
        config = ConfigurationProperties.fromFile(File(fileName))
    }

    fun print() {
        config.list().map{ it.second.map { println(it) }}
    }
}