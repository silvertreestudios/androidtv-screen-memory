param([Parameter(Mandatory=$true)][string]$SdkRoot,
      [Parameter(Mandatory=$true)][string]$KeyPath)
$ErrorActionPreference='Stop'
Set-Location $PSScriptRoot
function Run([string]$Exe, [string[]]$Arguments) {
    Write-Output ('RUN '+$Exe+' '+($Arguments -join ' '))
    $ErrorActionPreference='Continue'
    $output = & $Exe @Arguments 2>&1
    $exit = $LASTEXITCODE
    $output | ForEach-Object { Write-Output $_.ToString() }
    if($exit -ne 0){throw ('Command failed: '+$Exe+' exit='+$exit)}
    if(($output | Out-String) -match '(?im)^.*\bwarning\b.*$'){throw ('Warning diagnostic: '+$Exe)}
}
$b=Join-Path $PSScriptRoot 'build'
if(Test-Path $b){throw 'Build exists: preserve evidence and choose fresh checkout/output'}
New-Item -ItemType Directory -Force $b,($b+'/classes'),($b+'/test-classes'),($b+'/dex') | Out-Null
$bt=$SdkRoot+'/build-tools/35.0.0'
$android=$SdkRoot+'/platforms/android-31/android.jar'
Run ($bt+'/aapt2.exe') @('compile','--dir','res','-o',($b+'/compiled-res.zip'))
Run ($bt+'/aapt2.exe') @('link','-o',($b+'/resources.apk'),'--manifest','AndroidManifest.xml','-I',$android,'--min-sdk-version','31','--target-sdk-version','31','--java',($b+'/generated'),($b+'/compiled-res.zip'))
$src=@(Get-ChildItem 'src',($b+'/generated') -Recurse -Filter '*.java' | ForEach-Object {$_.FullName})
Run 'javac' (@('--release','11','-Xlint:all','-Werror','-classpath',$android,'-d',($b+'/classes'))+$src)
$pure=@('Memory','MemoryCodec','Catalog','Operation','RuntimeState','SelectGesture','UiPolicy') | ForEach-Object { 'src/org/offline/screenmemory/'+$_+'.java' }
Run 'javac' (@('--release','11','-Xlint:all','-Werror','-d',($b+'/test-classes'),'src/org/offline/binddiagnostic/BindingSession.java','tests/org/offline/binddiagnostic/BindingSessionTest.java','test/org/offline/binddiagnostic/BindingSessionTest.java','tests/org/offline/screenmemory/ScreenMemoryTest.java')+$pure)
Run 'java' @('-cp',($b+'/test-classes'),'org.offline.binddiagnostic.BindingSessionTest')
Run 'java' @('-cp',($b+'/test-classes'),'org.offline.binddiagnostic.ExtendedBindingSessionTest')
Run 'java' @('-cp',($b+'/test-classes'),'org.offline.screenmemory.ScreenMemoryTest')
$classes=@(Get-ChildItem ($b+'/classes') -Recurse -Filter '*.class' | ForEach-Object {$_.FullName})
# Invoke the official D8 Java entrypoint; no Gradle or third-party build dependency.
Run 'java' (@('-cp',($bt+'/lib/d8.jar'),'com.android.tools.r8.D8','--release','--min-api','31','--lib',$android,'--output',($b+'/dex'))+$classes)
Copy-Item ($b+'/resources.apk') ($b+'/unsigned.apk')
Run 'jar' @('uf',($b+'/unsigned.apk'),'-C',($b+'/dex'),'classes.dex')
Run ($bt+'/zipalign.exe') @('-f','4',($b+'/unsigned.apk'),($b+'/aligned.apk'))
if(!(Test-Path $KeyPath)) {
    # Standard nonsecret Android debug credential; never used for device/platform identity.
    $ErrorActionPreference='Continue'
    $keyOutput = & keytool -genkeypair -keystore $KeyPath -storepass android -keypass android -alias androiddebugkey -dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -keysize 2048 -validity 3650 2>&1
    $keyExit = $LASTEXITCODE
    $ErrorActionPreference='Stop'
    $keyOutput | ForEach-Object {Write-Output $_.ToString()}
    if($keyExit -ne 0){throw 'Task-local debug key generation failed'}
}
# Do not print signer arguments/passwords in the command log.
& ($bt+'/apksigner.bat') sign --ks $KeyPath --ks-pass pass:android --key-pass pass:android --out ($b+'/screen-memory-debug.apk') ($b+'/aligned.apk')
if($LASTEXITCODE -ne 0){throw 'APK signing failed'}
Run ($bt+'/apksigner.bat') @('verify','--verbose','--print-certs',($b+'/screen-memory-debug.apk'))
Run ($bt+'/zipalign.exe') @('-c','-v','4',($b+'/screen-memory-debug.apk'))
Run ($bt+'/aapt2.exe') @('dump','badging',($b+'/screen-memory-debug.apk'))
Run ($bt+'/aapt2.exe') @('dump','xmltree',($b+'/screen-memory-debug.apk'),'--file','AndroidManifest.xml')
# Keep the complete disassembly as independent review evidence; preserve exit/warning gates.
Run ($bt+'/dexdump.exe') @('-d',($b+'/dex/classes.dex')) | Tee-Object -FilePath ($b+'/dexdump.txt')
Get-FileHash ($b+'/screen-memory-debug.apk') -Algorithm SHA256
