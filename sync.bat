rclone sync testserver:mods/ libs/ --include "*.jar" -u -v --inplace
rclone copy testserver:jet/scripts src/main/kotlin/ --include "*.jet.kts" --include "*.jetlib.kt" -u -v --inplace
rclone copy src/main/kotlin/ testserver:jet/scripts --include "*.jet.kts" --include "*.jetlib.kt" -u -v --inplace