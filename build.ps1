param([Parameter(Mandatory=$true)][string]$SdkRoot,
      [Parameter(Mandatory=$true)][string]$KeyPath,
      [ValidateSet('Debug','Release')][string]$SigningMode='Debug',
      [ValidateRange(3,2100000000)][int]$VersionCode=3,
      [string]$VersionName='1.2')
# Keep native exit handling explicit in both Windows PowerShell 5 and PowerShell 7.
$PSNativeCommandUseErrorActionPreference=$false
$ErrorActionPreference='Stop'
Set-Location $PSScriptRoot
function Run([string]$Exe, [string[]]$Arguments) {
    Write-Output ('RUN '+$Exe+' '+($Arguments -join ' '))
    $ErrorActionPreference='Continue'
    $output = & $Exe @Arguments 2>&1
    $exit = $LASTEXITCODE
    $ErrorActionPreference='Stop'
    $output | ForEach-Object { Write-Output $_.ToString() }
    if($exit -ne 0){throw ('Command failed: '+$Exe+' exit='+$exit)}
    if(($output | Out-String) -match '(?im)^.*\bwarning\b.*$'){throw ('Warning diagnostic: '+$Exe)}
}
if ($VersionName -ne '1.2' -and $VersionName -notmatch '^1\.2\+sha\.[0-9a-f]{40}$') { throw 'Invalid versionName' }
if ($SigningMode -eq 'Release') {
    foreach ($name in @('RELEASE_KEYSTORE_PASSWORD','RELEASE_KEY_ALIAS','RELEASE_KEY_PASSWORD')) {
        if (![Environment]::GetEnvironmentVariable($name)) { throw ('Missing release signing variable: '+$name) }
    }
    if (!(Test-Path -LiteralPath $KeyPath)) { throw 'Release keystore missing; no debug fallback' }
}
$b=Join-Path $PSScriptRoot 'build'
if(Test-Path $b){throw 'Build exists: preserve evidence and choose fresh checkout/output'}
New-Item -ItemType Directory -Force $b,($b+'/classes'),($b+'/test-classes'),($b+'/dex') | Out-Null
$bt=Join-Path $SdkRoot 'build-tools/35.0.0'
$android=Join-Path $SdkRoot 'platforms/android-31/android.jar'
# $IsWindows is unavailable in Windows PowerShell 5.
$windowsHost=[Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
$nativeSuffix=if($windowsHost){'.exe'}else{''}
$scriptSuffix=if($windowsHost){'.bat'}else{''}
$aapt2=Join-Path $bt ('aapt2'+$nativeSuffix)
$zipalign=Join-Path $bt ('zipalign'+$nativeSuffix)
$dexdump=Join-Path $bt ('dexdump'+$nativeSuffix)
$apksigner=Join-Path $bt ('apksigner'+$scriptSuffix)
Run -Exe $aapt2 -Arguments @('compile','--dir','res','-o',($b+'/compiled-res.zip'))
Run -Exe $aapt2 -Arguments @('link','-o',($b+'/resources.apk'),'--manifest','AndroidManifest.xml','--replace-version','--version-code',([string]$VersionCode),'--version-name',$VersionName,'-I',$android,'--min-sdk-version','31','--target-sdk-version','31','--java',($b+'/generated'),($b+'/compiled-res.zip'))
$src=@(Get-ChildItem 'src',($b+'/generated') -Recurse -Filter '*.java' | ForEach-Object {$_.FullName})
Run -Exe 'javac' -Arguments (@('--release','11','-Xlint:all','-Werror','-classpath',$android,'-d',($b+'/classes'))+$src)
$pure=@('Memory','MemoryCodec','Catalog','Operation','RuntimeState','SelectGesture','UiPolicy') | ForEach-Object { 'src/org/offline/screenmemory/'+$_+'.java' }
Run -Exe 'javac' -Arguments (@('--release','11','-Xlint:all','-Werror','-d',($b+'/test-classes'),'src/org/offline/binddiagnostic/BindingSession.java','tests/org/offline/binddiagnostic/BindingSessionTest.java','test/org/offline/binddiagnostic/BindingSessionTest.java','tests/org/offline/screenmemory/ScreenMemoryTest.java')+$pure)
Run -Exe 'java' -Arguments @('-cp',($b+'/test-classes'),'org.offline.binddiagnostic.BindingSessionTest')
Run -Exe 'java' -Arguments @('-cp',($b+'/test-classes'),'org.offline.binddiagnostic.ExtendedBindingSessionTest')
Run -Exe 'java' -Arguments @('-cp',($b+'/test-classes'),'org.offline.screenmemory.ScreenMemoryTest')
$classes=@(Get-ChildItem ($b+'/classes') -Recurse -Filter '*.class' | ForEach-Object {$_.FullName})
# Invoke the official D8 Java entrypoint; no Gradle or third-party build dependency.
Run -Exe 'java' -Arguments (@('-cp',($bt+'/lib/d8.jar'),'com.android.tools.r8.D8','--release','--min-api','31','--lib',$android,'--output',($b+'/dex'))+$classes)
Copy-Item ($b+'/resources.apk') ($b+'/unsigned.apk')
Run -Exe 'jar' -Arguments @('uf',($b+'/unsigned.apk'),'-C',($b+'/dex'),'classes.dex')
Run -Exe $zipalign -Arguments @('-f','4',($b+'/unsigned.apk'),($b+'/aligned.apk'))
if($SigningMode -eq 'Debug' -and !(Test-Path $KeyPath)) {
    # Standard nonsecret Android debug credential; never used for device/platform identity.
    $ErrorActionPreference='Continue'
    $keyOutput = & keytool -genkeypair -keystore $KeyPath -storepass android -keypass android -alias androiddebugkey -dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -keysize 2048 -validity 3650 2>&1
    $keyExit = $LASTEXITCODE
    $ErrorActionPreference='Stop'
    $keyOutput | ForEach-Object {Write-Output $_.ToString()}
    if($keyExit -ne 0){throw 'Task-local debug key generation failed'}
}
# apksigner reads passwords from the environment; never print signing arguments.
$apk=if($SigningMode -eq 'Release'){ $b+'/screen-memory-release.apk' }else{ $b+'/screen-memory-debug.apk' }
if ($SigningMode -eq 'Release') {
    & $apksigner sign --ks $KeyPath --ks-key-alias $env:RELEASE_KEY_ALIAS --ks-pass env:RELEASE_KEYSTORE_PASSWORD --key-pass env:RELEASE_KEY_PASSWORD --out $apk ($b+'/aligned.apk')
} else {
    & $apksigner sign --ks $KeyPath --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out $apk ($b+'/aligned.apk')
}
if($LASTEXITCODE -ne 0){throw 'APK signing failed'}
# Compare actual APK signer with the selected keystore certificate.
$certPath=$b+'/signer.der'
if ($SigningMode -eq 'Release') {
    & keytool -exportcert -keystore $KeyPath -alias $env:RELEASE_KEY_ALIAS -storepass:env RELEASE_KEYSTORE_PASSWORD -file $certPath
} else {
    & keytool -exportcert -keystore $KeyPath -alias androiddebugkey -storepass android -file $certPath
}
if($LASTEXITCODE -ne 0){throw 'Signing certificate export failed'}
$expected=(Get-FileHash $certPath -Algorithm SHA256).Hash.ToLowerInvariant()
$signature=Run -Exe $apksigner -Arguments @('verify','--verbose','--print-certs',$apk)
$signature | Write-Output
if (($signature -join "`n") -notmatch ('(?im)^Signer #1 certificate SHA-256 digest: '+$expected+'\s*$')) { throw 'APK signer does not match selected key' }
Remove-Item -LiteralPath $certPath
Run -Exe $zipalign -Arguments @('-c','-v','4',$apk)
$badging=Run -Exe $aapt2 -Arguments @('dump','badging',$apk)
$badging | Write-Output
$packageLines=@($badging | Where-Object { $_ -match '^package: ' })
if ($packageLines.Count -ne 1 -or $packageLines[0] -notmatch (" versionCode='"+[regex]::Escape([string]$VersionCode)+"'") -or $packageLines[0] -notmatch (" versionName='"+[regex]::Escape($VersionName)+"'")) { throw 'APK version does not match requested identity' }
Run -Exe $aapt2 -Arguments @('dump','xmltree',$apk,'--file','AndroidManifest.xml')
# Keep the complete disassembly as independent review evidence; preserve exit/warning gates.
Run -Exe $dexdump -Arguments @('-d',($b+'/dex/classes.dex')) | Tee-Object -FilePath ($b+'/dexdump.txt')
Get-FileHash $apk -Algorithm SHA256
