rclone sync testserver:mods/ libs/ --include "*.jar" -u -v --inplace
rclone copy testserver:jet/scripts src/main/kotlin/ --include "*.kts" -u -v --inplace
rclone copy src/main/kotlin/ testserver:jet/scripts --include "*.kts" -u -v --inplace
