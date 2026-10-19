# サーバー自動更新（Fabric / Forge）

Minecraft 1.20.1 / Java 17 の Fabric・Forge 専用サーバーで、**26.12.1 以降**に対応します。

## 初回の導入・旧版からの移行

**26.11.x 以前のサーバーには、この自動更新機能がありません。初回だけサーバー側のJARを手動で入れ替えてください。**
クライアント側の自動更新や、起動スクリプトの設置だけでは、旧版のサーバーJARは更新されません。

1. サーバーのコンソールで `stop` を実行し、ワールドの保存とJavaプロセスの終了を確認します。
2. 対象サーバーの `mods/` にある既存のGuildQuests JARを、`mods/` の外にバックアップします。
3. [最新の安定版リリース](https://github.com/takoyaki150141/minecraft_mod_guild/releases/latest)から、使用中のローダー用JARを取得し、同じ `mods/` に配置します。Fabric用は `guildquests_fabric-1.20.1_<version>.jar`、Forge用は `guildquests_forge-1.20.1_<version>.jar` です。GuildQuestsのJARが1つだけになるようにしてください。
4. 既存の `world/` や `config/guildquests/` はそのまま使用します。クライアントも同じローダー・同じModバージョンに揃えます。
5. サーバーを起動し、**そのサーバーの** `logs/latest.log` に `[Server Update] Started: loader=fabric, current=...`（Forgeは `loader=forge`）と、`[Server Update] Settings loaded:` が出ることを確認します。`current` は手順3で配置した版です。

対応版を初めて起動すると `config/guildquests/server-update.json` を生成します。
サーバーコンソールでは `guildadmin update status`、ゲーム内では `/guildadmin update status` で状態を確認できます（OP Lv2）。
`guildadmin reload` は設定を読み直すコマンドです。実行中のModのバージョンを切り替えるには、サーバーの終了と起動が必要です。

### `update status` / `update check` が使えない場合

`Incorrect argument for command` や `Unknown or incomplete command` になる場合は、まずサーバー起動時に読み込んだGuildQuestsのバージョンを確認します。
FabricではMod一覧の `- guildquests 26.11.0` などが手掛かりです。上記の `[Server Update] Started:` がなければ、サーバー自動更新機能の起動を確認できていません。

26.11.x以前なら、上の初回移行を行ってください。すでにJARを入れ替えた場合は、起動中のJavaプロセスを終了したか、実際の起動先サーバーの `mods/` を変更したかを確認します。
更新用の設定ファイルを作成するだけでは、旧版にコマンドや自動更新機能は追加されません。

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
Fabricはサーバー起動JAR、Forgeはインストーラーが生成した `run.bat` と同じ場所です。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\start-server.ps1
```

ローダーは自動判定します。明示する場合:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\start-server.ps1 -Loader Forge
powershell -NoProfile -ExecutionPolicy Bypass -File .\start-server.ps1 -Loader Fabric
```

同じフォルダーに両方のローダーがある場合は `-Loader` を指定してください。
Forgeは公式の `libraries/net/minecraftforge/forge/<バージョン>/win_args.txt` から起動し、`user_jvm_args.txt` も読み込みます。
複数のForgeバージョンがある場合は使用する引数ファイルを指定します。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\start-server.ps1 -Loader Forge -ForgeArgsFile "libraries/net/minecraftforge/forge/1.20.1-47.2.0/win_args.txt"
```

メモリは `-MinMemory 1G -MaxMemory 4G`、Javaの実行ファイルは `-JavaPath "Javaのパス"` で変更できます。
メモリ指定はForgeの `user_jvm_args.txt` より優先します。
FabricのサーバーJAR名は `-ServerJar fabric-server-launch.jar` で変更できます。

更新のために停止した場合だけ、JARの適用完了を待って自動起動します。
通常の `stop` コマンドや、更新要求のないクラッシュでは再起動しません。
通常のForge `run.bat` や `java` から起動した場合も終了後の差し替えは行いますが、次の起動は手動です。
サーバー管理サービスを使う場合は、JARの適用完了後にMinecraftを起動してください。

## Linux / 既存の start.sh を使う場合

同じ場所に `start-server.sh` を配置して起動します。Forgeでは `unix_args.txt` を利用します。

```sh
sh start-server.sh
LOADER=forge sh start-server.sh
FORGE_ARGS_FILE=libraries/net/minecraftforge/forge/1.20.1-47.2.0/unix_args.txt sh start-server.sh
```

`JAVA_PATH`、`MIN_MEMORY`、`MAX_MEMORY`、`SERVER_JAR` も環境変数で指定できます。

既存の `start.sh` で `java ... -jar ...` を直接実行している場合、対応版のModは終了後にJARを差し替えますが、自動再起動は行いません。
自動再起動も使う場合は、同じサーバーフォルダーへ付属の `start-server.sh` を配置し、`start.sh` のJavaを起動している行を、たとえば次のように変更します。
メモリ・Javaのパス・Fabricの起動JAR名は、既存の起動設定に合わせてください。

```sh
# Fabric: サーバーフォルダーを作業ディレクトリにして実行
LOADER=fabric MIN_MEMORY=1G MAX_MEMORY=4G SERVER_JAR=fabric-server-launch.jar exec sh ./start-server.sh

# Forgeの場合
LOADER=forge MIN_MEMORY=1G MAX_MEMORY=4G exec sh ./start-server.sh
```

管理パネルやサービスで再起動を管理している場合は、使用中の管理方法に合わせ、更新JARの適用が完了してから次のサーバーを起動してください。
初回のJAR入れ替えは、この起動設定の変更より先に行います。

## 更新ファイルの検証と復旧

現在のローダーに合う安定版ReleaseのJARを選びます。

- Fabric: `guildquests_fabric-1.20.1_<数値バージョン>.jar`
- Forge: `guildquests_forge-1.20.1_<数値バージョン>.jar`

サイズ・提供時のSHA-256・全エントリーのCRCを検証し、Fabricは `fabric.mod.json`、Forgeは `META-INF/mods.toml` からMod ID・バージョン・ローダーと依存条件を確認します。
Forgeのバージョン範囲にはForgeと同じMaven形式を使用し、Javaの要件、必須Mod、導入済みの任意依存Modも検証します。
他ローダーのJARや非対応のMinecraftバージョンは適用せず、サーバーを稼働させたまま再試行します。
ドラフト・プレリリース・同じ版・古い版・該当JARのないReleaseも適用しません。

ダウンロードは `mods` 外の `config/guildquests/update/` で行います。
検証後、Java 17だけで動く独立ヘルパーが元のJavaプロセスの終了を待ち、現在のJARを `update/backups/` へ保存して差し替えます。
Windowsのファイルロックに対応し、バックアップは自動削除しません。

適用失敗時は元のJARを可能な限り復元し、`pending-install.properties` を残します。
付属スクリプトは起動前に未完了の適用を再試行し、失敗した場合は起動を止めます。
ログは `config/guildquests/update/installer.log` に出力します。

手動で元の版へ戻す場合はサーバーを終了し、現在のGuildQuests JARを取り除いて、バックアップを `.jar` に戻して `mods/` に配置します。
未完了の予約がある場合は `pending-install.properties` と `restart.requested` を退避してから起動してください。
