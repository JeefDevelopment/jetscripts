rclone sync jeefnet:mods/ libs/ --include "*.jar" -u -v --inplace
rclone copy jeefnet:jet/scripts src/main/kotlin/ --include "*.kts" -u -v --inplace
rclone copy src/main/kotlin/ jeefnet:jet/scripts --include "*.kts" -u -v --inplace
