# サーバー自動更新（NeoForge）

Minecraft 1.21.1 / Java 21 の NeoForge 専用サーバーに対応します。バージョンは **26.17.4** です。

初回はサーバーを終了し、既存の GuildQuests JAR を 26.17.4 JAR に置き換えます。
クライアントも同じModバージョンに揃えてください。

## 設定

`config/guildquests/server-update.json` を専用サーバーの初回起動時に自動生成します。
事前に設定する場合は `config-examples/server-update.json` を同じ場所へコピーします。
変更後は `/guildadmin reload` または管理GUIの設定再読込を実行します。

| 項目 | 初期値 | 内容 |
|---|---|---|
| `enabled` | `true` | 自動更新の有効・無効 |
| `checkOnStartup` | `true` | 起動・設定再読込の直後に確認。`false` は確認間隔の経過後 |
| `checkIntervalMinutes` | `30` | 定期確認の間隔（1〜10080分） |
| `updateDelayMinutes` | `30` | 最初の検出からダウンロード開始まで（0〜525600分）。`0` は即時、`60` は1時間、`1440` は1日 |
| `retryIntervalMinutes` | `5` | 確認・ダウンロード・適用準備が失敗した場合の再試行間隔（1〜1440分） |
| `shutdownAfterDownload` | `true` | 検証後に保存して正常停止。`false` は次の手動終了後に適用 |
| `notifyPlayers` | `true` | 検出・カウントダウン・準備完了をチャットに通知。ログは常に出力 |
| `countdownSeconds` | `[300,60,30,10,5,4,3,2,1]` | ダウンロード開始前の通知タイミング（残り秒）。空配列でカウントダウン無効 |
| `connectTimeoutSeconds` | `10` | 接続タイムアウト（1〜120秒） |
| `requestTimeoutSeconds` | `120` | 確認・ダウンロードのタイムアウト（1〜600秒） |
| `releaseApiUrl` | GitHub Releases API | 通常は変更不要。HTTPS またはローカル試験用の loopback HTTP |

時間は実時間で計測し、プレイヤーがいない間も確認します。
最初の検出時刻を `config/guildquests/update/detected-update.json` に保存するため、待機中の再起動でも期限を引き継ぎます。
確認の繰り返しや、待機中にさらに新しい版が公開された場合も最初の検出時刻から数えます。
待機期間の変更もその時刻を基準に反映します。
無効化すると待機・停止を中断し、未実行の適用予約を解除します。再度有効にした場合は保存した検出時刻を利用します。
初回の設定JSONが壊れている場合は自動更新を無効のままにし、再読込時の不正な設定は直前の有効な設定を保持します。

- `/guildadmin update status`: 状態と更新予定時刻（UTC）を確認（OP Lv2）。
- `/guildadmin update check`: 有効時に更新を即時確認（OP Lv2）。

シングルプレイやLAN公開の統合サーバーでは自動停止しません。

## 自動再起動を使う（Windows）

`start-server.ps1` をサーバーフォルダーへ配置し、そのフォルダーから起動します。
NeoForgeはインストーラーが生成した `run.bat` と同じ場所です。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\start-server.ps1
```

ローダーは自動判定します。明示する場合:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\start-server.ps1 -Loader NeoForge
```

NeoForge 1.21.1は `libraries/net/neoforged/neoforge/<バージョン>/win_args.txt` から起動し、`user_jvm_args.txt` も読み込みます。
複数のNeoForgeバージョンがある場合は使用する引数ファイルを指定します。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\start-server.ps1 -Loader NeoForge -ForgeArgsFile "libraries/net/neoforged/neoforge/21.1.267/win_args.txt"
```

メモリは `-MinMemory 1G -MaxMemory 4G`、Javaの実行ファイルは `-JavaPath "Javaのパス"` で変更できます。
メモリ指定は `user_jvm_args.txt` より優先します。

更新のために停止した場合だけ、JARの適用完了を待って自動起動します。
通常の `stop` コマンドや、更新要求のないクラッシュでは再起動しません。
通常のNeoForge `run.bat` や `java` から起動した場合も終了後の差し替えは行いますが、次の起動は手動です。
サーバー管理サービスを使う場合は、JARの適用完了後にMinecraftを起動してください。

## Linux

同じ場所に `start-server.sh` を配置して起動します。NeoForgeでは `unix_args.txt` を利用します。

```sh
sh start-server.sh
LOADER=neoforge sh start-server.sh
FORGE_ARGS_FILE=libraries/net/neoforged/neoforge/21.1.267/unix_args.txt sh start-server.sh
```

`JAVA_PATH`、`MIN_MEMORY`、`MAX_MEMORY`、`SERVER_JAR` も環境変数で指定できます。

## 更新ファイルの検証と復旧

現在のローダーに合う安定版ReleaseのJARを選びます。

- NeoForge: `guildquests_neoforge-1.21.1_<数値バージョン>.jar`

サイズ・GitHub Release AssetのSHA-256ダイジェスト（必須）・全エントリーのCRCを検証し、`META-INF/neoforge.mods.toml` からMod ID・バージョン・ローダーと依存条件を確認します。
バージョン範囲にはNeoForgeと同じMaven形式を使用し、Javaの要件、必須Mod、導入済みの任意依存Modも検証します。
他ローダーのJARや非対応のMinecraftバージョンは適用せず、サーバーを稼働させたまま再試行します。
ドラフト・プレリリース・同じ版・古い版・該当JARのないReleaseも適用しません。
SHA-256ダイジェストがないReleaseは適用しません。

ダウンロードは `mods` 外の `config/guildquests/update/` で行います。
検証後、Java 21だけで動く独立ヘルパーが元のJavaプロセスの終了を待ち、現在のJARを `update/backups/` へ保存して差し替えます。
Windowsのファイルロックに対応し、バックアップは自動削除しません。

適用失敗時は元のJARを可能な限り復元し、`pending-install.properties` を残します。
付属スクリプトは起動前に未完了の適用を再試行し、失敗した場合は起動を止めます。
ログは `config/guildquests/update/installer.log` に出力します。

手動で元の版へ戻す場合はサーバーを終了し、現在のGuildQuests JARを取り除いて、バックアップを `.jar` に戻して `mods/` に配置します。
未完了の予約がある場合は `pending-install.properties` と `restart.requested` を退避してから起動してください。
