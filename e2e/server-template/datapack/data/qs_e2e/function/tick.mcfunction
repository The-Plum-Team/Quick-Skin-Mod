# Keep disposable E2E players from displacing one another when they share the fixed spawn.
team join qs_e2e @a[team=!qs_e2e]
# Move every unowned entity below the world before it can enter deterministic screenshots.
execute as @e[type=!minecraft:player,tag=!qs_e2e_keep] at @s run tp @s ~ -1024 ~
