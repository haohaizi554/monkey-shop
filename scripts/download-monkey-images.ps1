# Downloads real monkey images via Openverse API (CC-licensed) into uploads/images/monkeys.
$ErrorActionPreference = 'Stop'
$outDir = "d:\desktop\project\JavaScript_MonkeyShop\uploads\images\monkeys"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
$h = @{ 'User-Agent' = $ua; 'Accept' = 'application/json' }
$dh = @{ 'User-Agent' = $ua }

$map = [ordered]@{
  '蜘蛛猴'  = @('spider monkey','Ateles monkey')
  '卷尾猴'  = @('capuchin monkey','Cebus capuchin')
  '松鼠猴'  = @('squirrel monkey','Saimiri')
  '猕猴'    = @('rhesus macaque','macaque monkey')
  '狒狒'    = @('baboon','Papio baboon')
  '狨猴'    = @('marmoset','Callithrix marmoset')
  '长尾猴'  = @('vervet monkey','Cercopithecus guenon')
  '疣猴'    = @('colobus monkey','colobus guereza')
  '叶猴'    = @('langur monkey','Trachypithecus langur')
  '山魈'    = @('mandrill','Mandrillus sphinx')
  '狐猴'    = @('ring-tailed lemur','lemur Madagascar')
  '懒猴'    = @('slow loris','Nycticebus loris')
  '指猴'    = @('aye-aye','Daubentonia aye-aye')
  '夜猴'    = @('night monkey','owl monkey Aotus')
  '绒猴'    = @('emperor tamarin','tamarin monkey')
  '僧面猴'  = @('saki monkey','Pithecia saki')
  '秃猴'    = @('uakari','bald uakari monkey')
  '红吼猴'  = @('red howler monkey','howler monkey')
  '白面猴'  = @('white-faced saki monkey','white faced capuchin')
  '黑叶猴'  = @('Francois langur','black langur')
  '金丝猴'  = @('golden snub-nosed monkey','Rhinopithecus')
  '戴胜猴'  = @('barbary macaque','macaque')
  '树熊猴'  = @('potto primate','Perodicticus potto')
  '婴猴'    = @('bushbaby galago','galago senegalensis')
}

function Search-Openverse($term, $retry) {
  $q = [uri]::EscapeDataString($term)
  $url = "https://api.openverse.org/v1/images/?q=$q&page_size=5&mature=false"
  for ($attempt = 0; $attempt -lt $retry; $attempt++) {
    try {
      $r = Invoke-RestMethod -Uri $url -Headers $h -TimeoutSec 30
      return $r.results
    } catch {
      if ($_.Exception.Response.StatusCode.value__ -eq 429) {
        Start-Sleep -Seconds (8 + $attempt * 6)
        continue
      }
      Start-Sleep -Seconds 2
    }
  }
  return $null
}

function Download-Url($url, $dest) {
  try {
    Invoke-WebRequest -Uri $url -OutFile $dest -Headers $dh -TimeoutSec 60
    $sz = (Get-Item $dest).Length
    return $sz
  } catch { return 0 }
}

$results = @()
$idx = 0
foreach ($breed in $map.Keys) {
  $idx++
  $done = $false
  $picked = ''
  foreach ($term in $map[$breed]) {
    $res = Search-Openverse $term 4
    if (-not $res) { continue }
    $pick = $res | Where-Object { $_.url -match '\.(jpe?g|png)$' -and $_.url -notmatch 'staticflickr\.com.*_\d\.jpg$' } | Select-Object -First 1
    if (-not $pick) { $pick = $res | Select-Object -First 1 }
    if (-not $pick) { continue }
    $dest = Join-Path $outDir ($breed + ".jpg")
    $sz = Download-Url $pick.url $dest
    if ($sz -gt 3000) {
      $picked = $pick.url
      $results += [PSCustomObject]@{ Breed=$breed; Term=$term; Bytes=$sz; OK=$true; Url=$picked }
      $done = $true
      break
    }
  }
  if (-not $done) { $results += [PSCustomObject]@{ Breed=$breed; Term=$term; Bytes=0; OK=$false; Url='' } }
  Start-Sleep -Milliseconds 1500   # be gentle with Openverse anonymous quota
}
$results | Format-Table -AutoSize
"OK: $(($results | Where-Object {$_.OK}).Count)/$($map.Count)"
