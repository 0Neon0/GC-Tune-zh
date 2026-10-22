$ErrorActionPreference = 'Stop'
# 注意：以下四个路径取自原作者机器环境，请在构建前改成本机实际路径。
$src = 'C:\Users\ttt79\reverse\poc_gctune'
$bt = 'C:\Android\sdk\build-tools\34.0.0'
$androidJar = 'C:\Android\sdk\platforms\android-35\android.jar'
$jdk = 'C:\Program Files\Eclipse Adoptium\jdk-11.0.32.9-hotspot\bin'

Push-Location $src
Remove-Item -Recurse -Force classes -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force classes | Out-Null

$srcFiles = @(Get-ChildItem -Path $src -Filter *.java | ForEach-Object { $_.Name })
Write-Output ("正在编译：" + ($srcFiles -join ', '))
& "$jdk\javac.exe" -source 1.8 -target 1.8 -nowarn -encoding UTF-8 -bootclasspath $androidJar -cp $androidJar -d classes $srcFiles
if ($LASTEXITCODE -ne 0) { throw "javac 编译失败（$LASTEXITCODE）" }
if (-not (Test-Path 'classes\com\poc\gctune\Main.class')) { throw 'javac 编译失败：未生成 Main.class' }
if (-not (Test-Path 'classes\com\poc\gctune\PrefsActivity.class')) { throw 'javac 输出缺少 PrefsActivity.class' }
if (-not (Test-Path 'classes\com\poc\gctune\SetupActivity.class')) { throw 'javac 输出缺少 SetupActivity.class' }

& "$jdk\jar.exe" cf classes.jar -C classes .
if ($LASTEXITCODE -ne 0) { throw "jar 打包失败（$LASTEXITCODE）" }
& "$bt\d8.bat" --release --lib $androidJar --output . classes.jar
if ($LASTEXITCODE -ne 0) { throw "d8 转换失败（$LASTEXITCODE）" }
if (-not (Test-Path 'classes.dex')) { throw 'd8 转换失败：未生成 classes.dex' }

Remove-Item gctune-unsigned.apk,gctune-aligned.apk,gctune-signed.apk,gctune-signed.apk.idsig -ErrorAction SilentlyContinue
& "$bt\aapt.exe" package -f -M AndroidManifest.xml -I $androidJar -F gctune-unsigned.apk
if ($LASTEXITCODE -ne 0) { throw "aapt 打包失败（$LASTEXITCODE）" }
& "$bt\aapt.exe" add gctune-unsigned.apk classes.dex | Out-Null
if ($LASTEXITCODE -ne 0) { throw "aapt 添加 classes.dex 失败（$LASTEXITCODE）" }
& "$bt\zipalign.exe" -f 4 gctune-unsigned.apk gctune-aligned.apk
if ($LASTEXITCODE -ne 0) { throw "zipalign 对齐失败（$LASTEXITCODE）" }
& "$bt\apksigner.bat" sign --ks ..\poc_gcrce\poc.keystore --ks-key-alias gd --ks-pass pass:android --key-pass pass:android --out gctune-signed.apk gctune-aligned.apk
if ($LASTEXITCODE -ne 0) { throw "apksigner 签名失败（$LASTEXITCODE）" }
if (-not (Test-Path 'gctune-signed.apk')) { throw '签名失败：未生成 gctune-signed.apk' }
Write-Output '构建成功'
Pop-Location
