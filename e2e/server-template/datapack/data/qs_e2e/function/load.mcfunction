# Runs once on world load (minecraft:load tag). Starts screenshots in a fixed location and daylight.
weather clear
gamerule doWeatherCycle false
gamerule doDaylightCycle false
gamerule spawnRadius 0
team add qs_e2e
team modify qs_e2e collisionRule never
time set day
