# Windows 开发辅助脚本

从仓库根目录迁移至此的 PowerShell 临时脚本,用于 Windows 开发机上的环境诊断
(查找 JDK / Android Studio / ADB)、测试日志分析和中文插件检查。

注意:部分脚本包含硬编码的本机路径(如 `f:\新建文件夹\octopus-mobile`),
在其他机器上使用前需自行调整。与构建流程无关,CI 不依赖这些脚本。
