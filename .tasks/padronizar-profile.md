## Padronizar comandos de profile — concluído

Formato final (aprovado pelo usuário):
- `siteblock create profile <nome>` — cria vazio
- `siteblock save profile <nome> [--force]` — cria com bloqueios ativos
- `siteblock delete profile <nome>` — apaga (confirma SIM)
- `siteblock profile <nome> show|apply|unblock [--force]`
- `siteblock profile <nome> add|remove <dominio...> [--for <duracao>]`
- `siteblock profile list`

Nenhum outro formato permitido para profile.

Arquivos alterados:
- sitelock-app/src/main/java/com/sitelock/cli/CreateProfileCommand.java (novo)
- sitelock-app/src/main/java/com/sitelock/cli/SaveProfileCommand.java (novo)
- sitelock-app/src/main/java/com/sitelock/cli/DeleteProfileCommand.java (novo)
- sitelock-app/src/main/java/com/sitelock/cli/ProfileCommand.java (removidos save/delete da lista)
- sitelock-app/src/main/java/com/sitelock/cli/ProfileAddCommand.java (mensagens atualizadas)
- sitelock-app/src/main/java/com/sitelock/cli/ProfileListCommand.java (mensagem atualizada)
- sitelock-app/src/main/java/com/sitelock/SiteLockApplication.java (registrados novos comandos)
- README.md (seção de comandos atualizada)
- .tasks/padronizar-profile.md (rastro)

Testes: ProfileEditTest#saveAndDeleteStillTakeTheirOwnName passa.
