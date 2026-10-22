# 本项目没有第三方库，规则留空即可。
# 保留 Core / MicService 等入口类，避免被 R8 改名后 Manifest 找不到。
-keep class com.phonemic.MainActivity { *; }
-keep class com.phonemic.MicService { *; }
-keep class com.phonemic.StopReceiver { *; }
