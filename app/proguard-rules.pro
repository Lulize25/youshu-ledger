# 记一笔 R8/ProGuard 规则
# 各依赖（Room / kotlinx-serialization / Compose / biometric / commons-csv）
# 均自带 consumer ProGuard 规则，此处仅补充项目特定保守规则。

# 序列化 DTO 与实体：成员名被 kotlinx.serialization 反射使用
-keepclassmembers class com.youshu.ledger.data.io.** {
    *;
}

# Room 实体与事务回调（保险起见，Room 生成代码已自带规则）
-keep class * extends androidx.room.RoomDatabase
-keepclassmembers @androidx.room.Entity class * {
    *;
}
