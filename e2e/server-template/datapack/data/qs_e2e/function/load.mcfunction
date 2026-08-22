# Runs once on world load (minecraft:load tag). Starts screenshots in a fixed location and daylight.
weather clear
gamerule minecraft:advance_weather false
gamerule minecraft:advance_time false
gamerule minecraft:respawn_radius 0
team add qs_e2e
team modify qs_e2e collisionRule never
time set day
