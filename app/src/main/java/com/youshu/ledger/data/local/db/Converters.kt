package com.youshu.ledger.data.local.db

import androidx.room.TypeConverter
import com.youshu.ledger.data.local.entity.CategoryType
import com.youshu.ledger.data.local.entity.TxnRole
import com.youshu.ledger.data.local.entity.TxnType

/** Room 枚举转换器：数据库中以字符串存储，读写时与 Kotlin 枚举互转 */
class Converters {

    @TypeConverter
    fun txnTypeToString(value: TxnType): String = value.name

    @TypeConverter
    fun stringToTxnType(value: String): TxnType = TxnType.valueOf(value)

    @TypeConverter
    fun txnRoleToString(value: TxnRole): String = value.name

    @TypeConverter
    fun stringToTxnRole(value: String): TxnRole = TxnRole.valueOf(value)

    @TypeConverter
    fun categoryTypeToString(value: CategoryType): String = value.name

    @TypeConverter
    fun stringToCategoryType(value: String): CategoryType = CategoryType.valueOf(value)
}
