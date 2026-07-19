param(
    [switch]$RunMaven,
    [string]$MavenTestPattern = "ApiRateLimitServiceTest,ApiRateLimitFilterTest,TurnstileVerifierTest,CaptchaServiceTest,CaptchaHttpTest,PiiCryptoServiceTest,PiiKeyMaterialProviderTest,PiiBlindIndexEntityListenerTest,PiiRetentionServiceTest,PiiPlaintextBackfillServiceTest,EdgeProxyConfigTest"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$Failures = [System.Collections.Generic.List[string]]::new()

function Add-Failure {
    param([string]$Message)
    [void]$script:Failures.Add($Message)
}

function Assert-File {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        Add-Failure "Missing required file: $Path"
        return $false
    }
    return $true
}

function Read-RequiredFile {
    param([string]$Path)
    if (Assert-File -Path $Path) {
        return Get-Content -LiteralPath $Path -Raw
    }
    return ""
}

function Assert-Match {
    param(
        [string]$Name,
        [string]$Text,
        [string]$Pattern,
        [string]$Message
    )
    if ($Text -notmatch $Pattern) {
        Add-Failure "${Name}: $Message"
    }
}

function Assert-NotMatch {
    param(
        [string]$Name,
        [string]$Text,
        [string]$Pattern,
        [string]$Message
    )
    if ($Text -match $Pattern) {
        Add-Failure "${Name}: $Message"
    }
}

function Invoke-CheckedCommand {
    param(
        [string]$Name,
        [string[]]$Arguments
    )
    Write-Host "==> $Name"
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $Arguments[0] $Arguments[1..($Arguments.Count - 1)] 2>&1 | ForEach-Object { Write-Host $_ }
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($LASTEXITCODE -ne 0) {
        Add-Failure "$Name failed with exit code $LASTEXITCODE"
    }
}

Write-Host "==> WS8 anti-abuse and data-protection checks"

$requiredFiles = @(
    "pom.xml",
    ".github/workflows/ci.yaml",
    "src/main/resources/application.yml",
    "src/main/resources/application-dev.yml",
    "src/main/resources/application-staging.yml",
    "src/main/resources/application-prod.yml",
    "src/main/java/com/example/monkey/shared/infrastructure/config/SecurityConfig.java",
    "src/main/java/com/example/monkey/user/interfaces/AuthController.java",
    "src/main/java/com/example/monkey/user/interfaces/UserController.java",
    "scripts/verify-runtime-data-protection.sh",
    "src/main/java/com/example/monkey/shared/domain/security/RateLimitPolicy.java",
    "src/main/java/com/example/monkey/shared/domain/security/ApiRateLimiter.java",
    "src/main/java/com/example/monkey/shared/application/security/ApiRateLimitOperation.java",
    "src/main/java/com/example/monkey/shared/infrastructure/security/RateLimitProperties.java",
    "src/main/java/com/example/monkey/shared/infrastructure/security/ApiRateLimitService.java",
    "src/main/java/com/example/monkey/shared/interfaces/security/ApiRateLimitFilter.java",
    "src/main/java/com/example/monkey/user/infrastructure/TurnstileVerifier.java",
    "src/main/java/com/example/monkey/shared/infrastructure/privacy/PiiCryptoService.java",
    "src/main/java/com/example/monkey/shared/infrastructure/privacy/PiiKeyMaterialProvider.java",
    "src/main/java/com/example/monkey/shared/infrastructure/privacy/PiiPlaintextBackfillService.java",
    "src/main/java/com/example/monkey/shared/infrastructure/privacy/PiiPlaintextBackfillRunner.java",
    "src/main/java/com/example/monkey/shared/infrastructure/privacy/EncryptedStringAttributeConverter.java",
    "src/main/java/com/example/monkey/shared/infrastructure/privacy/PiiBlindIndexEntityListener.java",
    "src/main/java/com/example/monkey/user/application/CaptchaService.java",
    "src/main/java/com/example/monkey/shared/interfaces/web/CaptchaHttp.java",
    "src/main/java/com/example/monkey/user/application/PiiRetentionService.java",
    "src/main/java/com/example/monkey/user/infrastructure/JpaPiiRetentionStore.java",
    "src/main/java/com/example/monkey/user/infrastructure/User.java",
    "src/main/java/com/example/monkey/user/infrastructure/Address.java",
    "src/main/java/com/example/monkey/order/infrastructure/Order.java",
    "src/main/resources/db/migration/V16__pii_encryption_columns.sql",
    "src/main/resources/db/migration/V18__user_email_pii_encryption.sql",
    "src/test/java/com/example/monkey/shared/infrastructure/security/RateLimitPropertiesTest.java",
    "src/test/java/com/example/monkey/shared/infrastructure/privacy/PiiCryptoServiceTest.java",
    "src/test/java/com/example/monkey/shared/infrastructure/privacy/PiiKeyMaterialProviderTest.java",
    "deploy/nginx/monkeyshop.conf",
    "docs/security/ws8.md",
    "helm/monkeyshop/values.yaml",
    "helm/monkeyshop/values-staging.yaml",
    "helm/monkeyshop/values-prod.yaml"
)

foreach ($file in $requiredFiles) {
    [void](Assert-File -Path $file)
}

$pom = Read-RequiredFile -Path "pom.xml"
$workflow = Read-RequiredFile -Path ".github/workflows/ci.yaml"
$application = Read-RequiredFile -Path "src/main/resources/application.yml"
$dev = Read-RequiredFile -Path "src/main/resources/application-dev.yml"
$staging = Read-RequiredFile -Path "src/main/resources/application-staging.yml"
$prod = Read-RequiredFile -Path "src/main/resources/application-prod.yml"
$securityConfig = Read-RequiredFile -Path "src/main/java/com/example/monkey/shared/infrastructure/config/SecurityConfig.java"
$authController = Read-RequiredFile -Path "src/main/java/com/example/monkey/user/interfaces/AuthController.java"
$userController = Read-RequiredFile -Path "src/main/java/com/example/monkey/user/interfaces/UserController.java"
$runtimeDataProtectionBash = Read-RequiredFile -Path "scripts/verify-runtime-data-protection.sh"
$rateLimitPolicy = Read-RequiredFile -Path "src/main/java/com/example/monkey/shared/domain/security/RateLimitPolicy.java"
$rateLimitPort = Read-RequiredFile -Path "src/main/java/com/example/monkey/shared/domain/security/ApiRateLimiter.java"
$rateLimitOperation = Read-RequiredFile -Path "src/main/java/com/example/monkey/shared/application/security/ApiRateLimitOperation.java"
$rateLimitProperties = Read-RequiredFile -Path "src/main/java/com/example/monkey/shared/infrastructure/security/RateLimitProperties.java"
$rateLimitPropertiesTest = Read-RequiredFile -Path "src/test/java/com/example/monkey/shared/infrastructure/security/RateLimitPropertiesTest.java"
$rateLimitService = Read-RequiredFile -Path "src/main/java/com/example/monkey/shared/infrastructure/security/ApiRateLimitService.java"
$rateLimitFilter = Read-RequiredFile -Path "src/main/java/com/example/monkey/shared/interfaces/security/ApiRateLimitFilter.java"
$turnstile = Read-RequiredFile -Path "src/main/java/com/example/monkey/user/infrastructure/TurnstileVerifier.java"
$captchaService = Read-RequiredFile -Path "src/main/java/com/example/monkey/user/application/CaptchaService.java"
$captchaHttp = Read-RequiredFile -Path "src/main/java/com/example/monkey/shared/interfaces/web/CaptchaHttp.java"
$piiCrypto = Read-RequiredFile -Path "src/main/java/com/example/monkey/shared/infrastructure/privacy/PiiCryptoService.java"
$piiProvider = Read-RequiredFile -Path "src/main/java/com/example/monkey/shared/infrastructure/privacy/PiiKeyMaterialProvider.java"
$piiBackfill = Read-RequiredFile -Path "src/main/java/com/example/monkey/shared/infrastructure/privacy/PiiPlaintextBackfillService.java"
$piiBackfillRunner = Read-RequiredFile -Path "src/main/java/com/example/monkey/shared/infrastructure/privacy/PiiPlaintextBackfillRunner.java"
$piiConverter = Read-RequiredFile -Path "src/main/java/com/example/monkey/shared/infrastructure/privacy/EncryptedStringAttributeConverter.java"
$piiListener = Read-RequiredFile -Path "src/main/java/com/example/monkey/shared/infrastructure/privacy/PiiBlindIndexEntityListener.java"
$piiRetention = Read-RequiredFile -Path "src/main/java/com/example/monkey/user/application/PiiRetentionService.java"
$piiRetentionStore = Read-RequiredFile -Path "src/main/java/com/example/monkey/user/infrastructure/JpaPiiRetentionStore.java"
$userEntity = Read-RequiredFile -Path "src/main/java/com/example/monkey/user/infrastructure/User.java"
$addressEntity = Read-RequiredFile -Path "src/main/java/com/example/monkey/user/infrastructure/Address.java"
$orderEntity = Read-RequiredFile -Path "src/main/java/com/example/monkey/order/infrastructure/Order.java"
$piiMigration = Read-RequiredFile -Path "src/main/resources/db/migration/V16__pii_encryption_columns.sql"
$emailPiiMigration = Read-RequiredFile -Path "src/main/resources/db/migration/V18__user_email_pii_encryption.sql"
$piiCryptoTest = Read-RequiredFile -Path "src/test/java/com/example/monkey/shared/infrastructure/privacy/PiiCryptoServiceTest.java"
$piiProviderTest = Read-RequiredFile -Path "src/test/java/com/example/monkey/shared/infrastructure/privacy/PiiKeyMaterialProviderTest.java"
$nginx = Read-RequiredFile -Path "deploy/nginx/monkeyshop.conf"
$docs = Read-RequiredFile -Path "docs/security/ws8.md"
$helmValues = Read-RequiredFile -Path "helm/monkeyshop/values.yaml"
$helmStaging = Read-RequiredFile -Path "helm/monkeyshop/values-staging.yaml"
$helmProd = Read-RequiredFile -Path "helm/monkeyshop/values-prod.yaml"

Assert-Match -Name "pom.xml" -Text $pom -Pattern "bucket4j_jdk17-core" -Message "must include Bucket4j for API rate limiting"
Assert-Match -Name "pom.xml" -Text $pom -Pattern "<artifactId>tink</artifactId>" -Message "must include Google Tink for PII encryption"

Assert-Match -Name "application.yml" -Text $application -Pattern "http-only:\s+true" -Message "must force HTTP-only session cookies"
Assert-Match -Name "application.yml" -Text $application -Pattern "secure:\s+\$\{SESSION_COOKIE_SECURE:true\}" -Message "must default session cookies to Secure"
Assert-Match -Name "application.yml" -Text $application -Pattern "same-site:\s+strict" -Message "must use SameSite=Strict session cookies"
Assert-Match -Name "application.yml" -Text $application -Pattern "timeout:\s+\$\{SESSION_TIMEOUT:30m\}" -Message "must cap sessions at 30 minutes by default"
Assert-Match -Name "application.yml" -Text $application -Pattern "block-seconds:\s+\$\{APP_WAF_HONEYPOT_BLOCK_SECONDS:86400\}" -Message "must default honeypot WAF blocks to 24 hours"
Assert-Match -Name "application.yml" -Text $application -Pattern "backfill:\s+enabled:\s+\$\{APP_PII_BACKFILL_ENABLED:false\}" -Message "must keep legacy PII backfill disabled by default"
Assert-Match -Name "application.yml" -Text $application -Pattern "previous-aes-keys-base64:\s+\$\{APP_PII_PREVIOUS_AES_KEYS_BASE64:\}" -Message "must allow historical AES keys for PII key rotation"
Assert-Match -Name "application.yml" -Text $application -Pattern "previous-aes-ciphertexts:\s+\$\{APP_PII_VAULT_PREVIOUS_AES_CIPHERTEXTS:\}" -Message "must allow historical Vault-wrapped AES keys for PII key rotation"
Assert-Match -Name "application.yml" -Text $application -Pattern "connection-timeout:\s+\$\{SERVER_TOMCAT_CONNECTION_TIMEOUT:5s\}" -Message "must guard Tomcat against slow clients"
Assert-Match -Name "SecurityConfig.java" -Text $securityConfig -Pattern "maximumSessions\(1\)" -Message "must restrict concurrent sessions"
Assert-Match -Name "verify-runtime-data-protection.sh" -Text $runtimeDataProtectionBash -Pattern "APP_PII_ENCRYPTION_ENABLED" -Message "must verify runtime PII encryption flags on Linux compose hosts"
Assert-Match -Name "verify-runtime-data-protection.sh" -Text $runtimeDataProtectionBash -Pattern "APP_PII_ALLOW_PLAINTEXT_READ" -Message "must verify strict plaintext-read mode on Linux compose hosts"
Assert-Match -Name "verify-runtime-data-protection.sh" -Text $runtimeDataProtectionBash -Pattern "REGEXP '\^enc:v1:" -Message "must verify complete ciphertext envelopes on Linux compose hosts"
Assert-Match -Name "verify-runtime-data-protection.sh" -Text $runtimeDataProtectionBash -Pattern "\^\[0-9a-f\]\{64\}\$" -Message "must verify phone blind indexes on Linux compose hosts"
Assert-Match -Name "verify-runtime-data-protection.sh" -Text $runtimeDataProtectionBash -Pattern "--require-populated-pii" -Message "must support populated PII evidence on Linux compose hosts"
Assert-Match -Name "verify-runtime-data-protection.sh" -Text $runtimeDataProtectionBash -Pattern "does not print\s+secrets or raw PII" -Message "must document non-disclosure of secrets and raw PII"

foreach ($profile in @(@{ Name = "application-staging.yml"; Text = $staging }, @{ Name = "application-prod.yml"; Text = $prod })) {
    Assert-Match -Name $profile.Name -Text $profile.Text -Pattern "require-redis-state:\s+\$\{APP_AUTH_REQUIRE_REDIS_STATE:true\}" -Message "must fail closed for auth state without Redis"
    Assert-Match -Name $profile.Name -Text $profile.Text -Pattern "require-redis-state:\s+\$\{APP_RATE_LIMIT_REQUIRE_REDIS_STATE:true\}" -Message "must fail closed for API rate-limit state without Redis"
    Assert-Match -Name $profile.Name -Text $profile.Text -Pattern "provider:\s+\$\{APP_AUTH_CAPTCHA_PROVIDER:turnstile\}" -Message "must use Turnstile by default"
    Assert-Match -Name $profile.Name -Text $profile.Text -Pattern "enabled:\s+\$\{APP_PII_ENCRYPTION_ENABLED:true\}" -Message "must enable PII encryption"
    Assert-Match -Name $profile.Name -Text $profile.Text -Pattern "key-provider:\s+\$\{APP_PII_KEY_PROVIDER:vault-transit\}" -Message "must release PII keys through Vault Transit"
    Assert-Match -Name $profile.Name -Text $profile.Text -Pattern "allow-plaintext-read:\s+\$\{APP_PII_ALLOW_PLAINTEXT_READ:false\}" -Message "must reject plaintext PII reads"
    Assert-Match -Name $profile.Name -Text $profile.Text -Pattern "enforce:\s+\$\{APP_PII_ROTATION_ENFORCE:true\}" -Message "must enforce PII key rotation"
}

Assert-Match -Name "RateLimitPolicy.java" -Text $rateLimitPolicy -Pattern 'LOGIN\("login",\s*5,\s*Duration\.ofMinutes\(1\)\)' -Message "must cap login to 5 requests per minute"
Assert-Match -Name "RateLimitPolicy.java" -Text $rateLimitPolicy -Pattern 'REGISTER\("register",\s*120,\s*Duration\.ofHours\(1\)\)' -Message "must keep the lenient registration fallback used by local/default profiles"
Assert-Match -Name "RateLimitProperties.java" -Text $rateLimitProperties -Pattern 'ConfigurationProperties\(prefix\s*=\s*"monkeyshop\.rate-limit"\)' -Message "must bind typed registration quotas"
Assert-Match -Name "RateLimitProperties.java" -Text $rateLimitProperties -Pattern 'DEFAULT_REGISTER_CAPACITY\s*=\s*120L' -Message "must keep the usable default registration quota"
foreach ($profile in @(@{ Name = "application.yml"; Text = $application }, @{ Name = "application-dev.yml"; Text = $dev })) {
    Assert-Match -Name $profile.Name -Text $profile.Text -Pattern 'edge-capacity:\s+\$\{MONKEYSHOP_RATE_LIMIT_REGISTER_EDGE_CAPACITY:120\}' -Message "must allow normal local registration retries at the edge"
    Assert-Match -Name $profile.Name -Text $profile.Text -Pattern 'edge-window:\s+\$\{MONKEYSHOP_RATE_LIMIT_REGISTER_EDGE_WINDOW:1h\}' -Message "must use a one-hour local edge window"
    Assert-Match -Name $profile.Name -Text $profile.Text -Pattern 'identity-capacity:\s+\$\{MONKEYSHOP_RATE_LIMIT_REGISTER_IDENTITY_CAPACITY:120\}' -Message "must allow normal local retries per identity"
    Assert-Match -Name $profile.Name -Text $profile.Text -Pattern 'identity-window:\s+\$\{MONKEYSHOP_RATE_LIMIT_REGISTER_IDENTITY_WINDOW:1h\}' -Message "must use a one-hour local identity window"
}
foreach ($profile in @(@{ Name = "application-staging.yml"; Text = $staging }, @{ Name = "application-prod.yml"; Text = $prod })) {
    Assert-Match -Name $profile.Name -Text $profile.Text -Pattern 'edge-capacity:\s+\$\{MONKEYSHOP_RATE_LIMIT_REGISTER_EDGE_CAPACITY:20\}' -Message "must retain a production edge abuse threshold"
    Assert-Match -Name $profile.Name -Text $profile.Text -Pattern 'edge-window:\s+\$\{MONKEYSHOP_RATE_LIMIT_REGISTER_EDGE_WINDOW:15m\}' -Message "must retain the production edge window"
    Assert-Match -Name $profile.Name -Text $profile.Text -Pattern 'identity-capacity:\s+\$\{MONKEYSHOP_RATE_LIMIT_REGISTER_IDENTITY_CAPACITY:5\}' -Message "must retain a production identity abuse threshold"
    Assert-Match -Name $profile.Name -Text $profile.Text -Pattern 'identity-window:\s+\$\{MONKEYSHOP_RATE_LIMIT_REGISTER_IDENTITY_WINDOW:1h\}' -Message "must retain the production identity window"
}
Assert-Match -Name "RateLimitPropertiesTest.java" -Text $rateLimitPropertiesTest -Pattern 'assertRegister\(defaults\.register\(\),\s*120,\s*Duration\.ofHours\(1\),\s*120,\s*Duration\.ofHours\(1\)\)' -Message "must test default quotas"
Assert-Match -Name "RateLimitPropertiesTest.java" -Text $rateLimitPropertiesTest -Pattern 'assertRegister\(prod\.register\(\),\s*20,\s*Duration\.ofMinutes\(15\),\s*5,\s*Duration\.ofHours\(1\)\)' -Message "must test production quotas"
Assert-Match -Name "RateLimitPropertiesTest.java" -Text $rateLimitPropertiesTest -Pattern 'assertRegister\(staging\.register\(\),\s*20,\s*Duration\.ofMinutes\(15\),\s*5,\s*Duration\.ofHours\(1\)\)' -Message "must test staging quotas"
Assert-Match -Name "RateLimitPolicy.java" -Text $rateLimitPolicy -Pattern 'ORDER\("order",\s*10,\s*Duration\.ofMinutes\(1\)\)' -Message "must cap order creation to 10 requests per minute"
Assert-Match -Name "RateLimitPolicy.java" -Text $rateLimitPolicy -Pattern 'SEARCH\("search",\s*30,\s*Duration\.ofMinutes\(1\)\)' -Message "must cap product search to 30 requests per minute"
Assert-Match -Name "RateLimitPolicy.java" -Text $rateLimitPolicy -Pattern 'UPLOAD\("upload",\s*10,\s*Duration\.ofMinutes\(1\)\)' -Message "must cap uploads to 10 requests per minute"
Assert-Match -Name "RateLimitPolicy.java" -Text $rateLimitPolicy -Pattern 'DEFAULT\("default",\s*120,\s*Duration\.ofMinutes\(1\)\)' -Message "must cap default API traffic"
Assert-Match -Name "ApiRateLimiter.java" -Text $rateLimitPort -Pattern "interface ApiRateLimiter" -Message "must expose a framework-free rate-limiter port"
Assert-Match -Name "ApiRateLimitOperation.java" -Text $rateLimitOperation -Pattern "enum ApiRateLimitOperation" -Message "must keep endpoint operation names in the application layer"
Assert-Match -Name "ApiRateLimitService.java" -Text $rateLimitService -Pattern "implements ApiRateLimiter" -Message "must implement the domain rate-limiter port"
Assert-Match -Name "ApiRateLimitService.java" -Text $rateLimitService -Pattern 'consumeDimension\(policy,\s*"ip"' -Message "must charge IP rate-limit dimension"
Assert-Match -Name "ApiRateLimitService.java" -Text $rateLimitService -Pattern 'consumeDimension\(policy,\s*"user"' -Message "must charge authenticated-user rate-limit dimension"
Assert-Match -Name "ApiRateLimitService.java" -Text $rateLimitService -Pattern 'consumeDimension\(policy,\s*"endpoint"' -Message "must charge endpoint rate-limit dimension"
Assert-Match -Name "ApiRateLimitService.java" -Text $rateLimitService -Pattern 'register\.edgeCapacity\(\)' -Message "must apply the typed registration edge quota"
Assert-Match -Name "ApiRateLimitService.java" -Text $rateLimitService -Pattern 'register\.edgeWindow\(\)' -Message "must apply the typed registration edge window"
Assert-Match -Name "ApiRateLimitService.java" -Text $rateLimitService -Pattern 'register\.identityCapacity\(\)' -Message "must apply the typed registration identity quota"
Assert-Match -Name "ApiRateLimitService.java" -Text $rateLimitService -Pattern 'register\.identityWindow\(\)' -Message "must apply the typed registration identity window"
Assert-Match -Name "ApiRateLimitService.java" -Text $rateLimitService -Pattern "REDIS_LIMIT_PREFIX" -Message "must namespace Redis rate counters"
Assert-Match -Name "ApiRateLimitService.java" -Text $rateLimitService -Pattern "STATE_UNAVAILABLE_MESSAGE" -Message "must fail closed when required rate-limit state is unavailable"
Assert-Match -Name "ApiRateLimitService.java" -Text $rateLimitService -Pattern "REDIS_BLOCK_PREFIX" -Message "must persist WAF honeypot blocks"
Assert-Match -Name "ApiRateLimitService.java" -Text $rateLimitService -Pattern "DEFAULT_HONEYPOT_BLOCK\s*=\s*Duration\.ofHours\(24\)" -Message "must keep honeypot WAF block duration at 24 hours"
Assert-Match -Name "ApiRateLimitService.java" -Text $rateLimitService -Pattern "redisTemplate\.opsForValue\(\)\.set\(key,\s*`"1`",\s*honeypotBlockDuration\)" -Message "must persist honeypot WAF blocks with the configured TTL"
Assert-Match -Name "ApiRateLimitServiceTest.java" -Text (Read-RequiredFile -Path "src/test/java/com/example/monkey/shared/infrastructure/security/ApiRateLimitServiceTest.java") -Pattern "eq\(Duration\.ofHours\(24\)\)" -Message "must prove honeypot WAF blocks are written with a 24 hour TTL"
Assert-Match -Name "ApiRateLimitFilter.java" -Text $rateLimitFilter -Pattern "Retry-After" -Message "must emit Retry-After on 429 responses"
Assert-Match -Name "ApiRateLimitFilter.java" -Text $rateLimitFilter -Pattern '"/api/\.env"' -Message "must catch API honeypot probes"
Assert-Match -Name "ApiRateLimitFilter.java" -Text $rateLimitFilter -Pattern '"/admin/secret"' -Message "must catch admin honeypot probes"
Assert-Match -Name "ApiRateLimitFilter.java" -Text $rateLimitFilter -Pattern "ApiRateLimitOperation\.LOGIN" -Message "must map login requests without importing domain policies"
Assert-Match -Name "ApiRateLimitFilter.java" -Text $rateLimitFilter -Pattern "ApiRateLimitOperation\.REGISTER" -Message "must map registration requests without importing domain policies"
Assert-Match -Name "ApiRateLimitFilter.java" -Text $rateLimitFilter -Pattern "ApiRateLimitOperation\.ORDER" -Message "must map order creation requests without importing domain policies"
Assert-Match -Name "ApiRateLimitFilter.java" -Text $rateLimitFilter -Pattern "ApiRateLimitOperation\.SEARCH" -Message "must map product search requests without importing domain policies"
Assert-Match -Name "ApiRateLimitFilter.java" -Text $rateLimitFilter -Pattern "ApiRateLimitOperation\.UPLOAD" -Message "must map upload requests without importing domain policies"

Assert-Match -Name "TurnstileVerifier.java" -Text $turnstile -Pattern "challenges\.cloudflare\.com/turnstile/v0/siteverify" -Message "must verify Turnstile tokens through Siteverify"
Assert-Match -Name "TurnstileVerifier.java" -Text $turnstile -Pattern "TOKEN_REPLAY_MESSAGE" -Message "must reject replayed human-verification tokens"
Assert-Match -Name "TurnstileVerifier.java" -Text $turnstile -Pattern "setIfAbsent" -Message "must burn tokens before verification with Redis when available"
Assert-Match -Name "TurnstileVerifier.java" -Text $turnstile -Pattern "actionMatches" -Message "must bind tokens to expected actions"
Assert-Match -Name "TurnstileVerifier.java" -Text $turnstile -Pattern "hostnameMatches" -Message "must bind tokens to the expected hostname"
Assert-Match -Name "CaptchaService.java" -Text $captchaService -Pattern 'CaptchaChallengeResult\.external\("turnstile",\s*turnstileSiteKey\)' -Message "must switch captcha creation to Turnstile challenges"
Assert-Match -Name "CaptchaService.java" -Text $captchaService -Pattern "humanVerificationService\.verify\(inputCode,\s*action,\s*remoteIp\)" -Message "must validate external captcha tokens through the human-verification port"
Assert-Match -Name "CaptchaHttp.java" -Text $captchaHttp -Pattern 'CAPTCHA_PROVIDER_HEADER\s*,\s*challenge\.provider\(\)' -Message "must advertise Turnstile metadata"
Assert-Match -Name "CaptchaHttp.java" -Text $captchaHttp -Pattern 'TURNSTILE_SITE_KEY_HEADER\s*,\s*challenge\.siteKey\(\)' -Message "must advertise Turnstile site keys"
Assert-Match -Name "AuthController.java" -Text $authController -Pattern 'ACTION_LOGIN\s+=\s+"login"' -Message "must bind login to a Turnstile action"
Assert-Match -Name "AuthController.java" -Text $authController -Pattern 'ACTION_REGISTER\s+=\s+"register"' -Message "must bind registration to a Turnstile action"
Assert-Match -Name "AuthController.java" -Text $authController -Pattern 'ACTION_PASSWORD_RESET_REQUEST\s+=\s+"password-reset-request"' -Message "must bind password reset request to a Turnstile action"
Assert-Match -Name "AuthController.java" -Text $authController -Pattern 'ACTION_PASSWORD_RESET\s+=\s+"password-reset"' -Message "must bind password reset completion to a Turnstile action"
Assert-Match -Name "UserController.java" -Text $userController -Pattern 'ACTION_CHANGE_PASSWORD\s+=\s+"change-password"' -Message "must bind password change to a Turnstile action"

Assert-Match -Name "PiiCryptoService.java" -Text $piiCrypto -Pattern "com\.google\.crypto\.tink" -Message "must use Tink-backed encryption"
Assert-Match -Name "PiiCryptoService.java" -Text $piiCrypto -Pattern "AesGcmJce" -Message "must use AES-GCM"
Assert-Match -Name "PiiCryptoService.java" -Text $piiCrypto -Pattern "ENCRYPTION_PREFIX" -Message "must mark ciphertext format"
Assert-Match -Name "PiiCryptoService.java" -Text $piiCrypto -Pattern "blindIndexPhone" -Message "must maintain phone blind indexes"
Assert-Match -Name "PiiCryptoService.java" -Text $piiCrypto -Pattern "PII plaintext read is not allowed" -Message "must reject plaintext reads when configured"
Assert-Match -Name "PiiCryptoService.java" -Text $piiCrypto -Pattern "aeadByVersion" -Message "must keep historical AEADs available for key rotation"
Assert-Match -Name "PiiCryptoService.java" -Text $piiCrypto -Pattern "storedKeyVersion" -Message "must choose decryption keys from ciphertext key versions"
Assert-Match -Name "PiiCryptoService.java" -Text $piiCrypto -Pattern "previous AES keys must not include the active key version" -Message "must reject ambiguous PII key-rotation configuration"
Assert-Match -Name "PiiPlaintextBackfillService.java" -Text $piiBackfill -Pattern "JdbcTemplate" -Message "must bypass JPA converters during legacy plaintext backfill"
Assert-Match -Name "PiiPlaintextBackfillService.java" -Text $piiBackfill -Pattern "backfillLegacyPlaintext" -Message "must provide a controlled legacy plaintext rewrite"
Assert-Match -Name "PiiPlaintextBackfillService.java" -Text $piiBackfill -Pattern "APP_PII_ENCRYPTION_ENABLED=true" -Message "must refuse backfill when encryption is disabled"
Assert-Match -Name "PiiPlaintextBackfillService.java" -Text $piiBackfill -Pattern "blindIndexPhone" -Message "must refresh phone blind indexes from plaintext values"
Assert-Match -Name "PiiPlaintextBackfillService.java" -Text $piiBackfill -Pattern "FROM ``user``" -Message "must scan legacy user PII"
Assert-Match -Name "PiiPlaintextBackfillService.java" -Text $piiBackfill -Pattern "FROM ``address``" -Message "must scan legacy address PII"
Assert-Match -Name "PiiPlaintextBackfillService.java" -Text $piiBackfill -Pattern "FROM ``orders``" -Message "must scan legacy order PII"
Assert-Match -Name "PiiPlaintextBackfillRunner.java" -Text $piiBackfillRunner -Pattern "ConditionalOnProperty" -Message "must guard the plaintext backfill runner behind an explicit property"
Assert-Match -Name "PiiPlaintextBackfillRunner.java" -Text $piiBackfillRunner -Pattern "app.pii.backfill" -Message "must bind the backfill runner to the documented config namespace"
Assert-Match -Name "PiiKeyMaterialProvider.java" -Text $piiProvider -Pattern "VAULT_TRANSIT" -Message "must support Vault Transit key release"
Assert-Match -Name "PiiKeyMaterialProvider.java" -Text $piiProvider -Pattern "X-Vault-Token" -Message "must authenticate Vault Transit decrypt calls"
Assert-Match -Name "PiiKeyMaterialProvider.java" -Text $piiProvider -Pattern "PII key rotation window exceeded" -Message "must enforce key rotation windows"
Assert-Match -Name "PiiKeyMaterialProvider.java" -Text $piiProvider -Pattern "previous-aes-keys-base64" -Message "must load historical env AES keys for rotation windows"
Assert-Match -Name "PiiKeyMaterialProvider.java" -Text $piiProvider -Pattern "previous-aes-ciphertexts" -Message "must load historical Vault-wrapped AES keys for rotation windows"
Assert-Match -Name "PiiKeyMaterialProvider.java" -Text $piiProvider -Pattern "parseVersionedEntries" -Message "must require versioned historical key material"
Assert-Match -Name "PiiCryptoServiceTest.java" -Text $piiCryptoTest -Pattern "rotatedKeyReadsPreviousVersionAndWritesWithActiveVersion" -Message "must prove rotated deployments read old ciphertext and write with the active key"
Assert-Match -Name "PiiCryptoServiceTest.java" -Text $piiCryptoTest -Pattern "rotatedKeyRejectsPreviousVersionWhenHistoryKeyIsMissing" -Message "must prove old ciphertext is rejected when historical key material is absent"
Assert-Match -Name "PiiKeyMaterialProviderTest.java" -Text $piiProviderTest -Pattern "loadsPreviousEnvironmentAesKeysForKeyRotation" -Message "must prove env historical AES key loading"
Assert-Match -Name "PiiKeyMaterialProviderTest.java" -Text $piiProviderTest -Pattern "v1=vault:v1:old" -Message "must prove Vault Transit historical AES key unwrapping"
Assert-Match -Name "EncryptedStringAttributeConverter.java" -Text $piiConverter -Pattern "convertToDatabaseColumn" -Message "must encrypt PII before database writes"
Assert-Match -Name "PiiBlindIndexEntityListener.java" -Text $piiListener -Pattern "PhoneBlindIndexTarget" -Message "must populate phone blind indexes through the shared PII contract"
Assert-Match -Name "PiiBlindIndexEntityListener.java" -Text $piiListener -Pattern "setPhoneBlindIndex" -Message "must write calculated phone blind indexes before persistence"
Assert-Match -Name "User.java" -Text $userEntity -Pattern "EncryptedStringAttributeConverter[\s\S]+private String phone;" -Message "must encrypt user phone"
Assert-Match -Name "User.java" -Text $userEntity -Pattern "EncryptedStringAttributeConverter[\s\S]+private String email;" -Message "must encrypt user email"
Assert-Match -Name "User.java" -Text $userEntity -Pattern "phone_hmac" -Message "must store user phone blind index"
Assert-Match -Name "Address.java" -Text $addressEntity -Pattern "EncryptedStringAttributeConverter[\s\S]+private String receiverName;" -Message "must encrypt address receiver name"
Assert-Match -Name "Address.java" -Text $addressEntity -Pattern "EncryptedStringAttributeConverter[\s\S]+private String phone;" -Message "must encrypt address phone"
Assert-Match -Name "Address.java" -Text $addressEntity -Pattern "EncryptedStringAttributeConverter[\s\S]+private String detailAddress;" -Message "must encrypt address detail"
Assert-Match -Name "Address.java" -Text $addressEntity -Pattern "phone_hmac" -Message "must store address phone blind index"
Assert-Match -Name "Order.java" -Text $orderEntity -Pattern "EncryptedStringAttributeConverter[\s\S]+private String buyerName;" -Message "must encrypt order buyer name"
Assert-Match -Name "Order.java" -Text $orderEntity -Pattern "EncryptedStringAttributeConverter[\s\S]+private String receiverName;" -Message "must encrypt order receiver name"
Assert-Match -Name "Order.java" -Text $orderEntity -Pattern "EncryptedStringAttributeConverter[\s\S]+private String receiverPhone;" -Message "must encrypt order receiver phone"
Assert-Match -Name "Order.java" -Text $orderEntity -Pattern "EncryptedStringAttributeConverter[\s\S]+private String addressSnapshot;" -Message "must encrypt order address snapshot"
Assert-Match -Name "Order.java" -Text $orderEntity -Pattern "receiver_phone_hmac" -Message "must store order receiver phone blind index"
Assert-Match -Name "Order.java" -Text $orderEntity -Pattern "setPhoneBlindIndex[\s\S]+receiverPhoneHmac" -Message "must map the shared blind-index contract to the order receiver phone index"
Assert-Match -Name "V16__pii_encryption_columns.sql" -Text $piiMigration -Pattern "phone_hmac" -Message "must migrate user/address blind indexes"
Assert-Match -Name "V16__pii_encryption_columns.sql" -Text $piiMigration -Pattern "receiver_phone_hmac" -Message "must migrate order blind indexes"
Assert-Match -Name "V18__user_email_pii_encryption.sql" -Text $emailPiiMigration -Pattern "MODIFY COLUMN ``email`` VARCHAR\(1024\)" -Message "must expand user email storage for encrypted ciphertext"
Assert-Match -Name "V18__user_email_pii_encryption.sql" -Text $emailPiiMigration -Pattern "DROP INDEX ``idx_user_email``" -Message "must remove the plaintext email lookup index"

Assert-Match -Name "PiiRetentionService.java" -Text $piiRetention -Pattern "forgetUser" -Message "must expose PII erasure service flow"
Assert-Match -Name "PiiRetentionService.java" -Text $piiRetention -Pattern "OrderStatus\.COMPLETED" -Message "must anonymize completed orders"
Assert-Match -Name "PiiRetentionService.java" -Text $piiRetention -Pattern "OrderStatus\.REFUNDED" -Message "must anonymize refunded orders"
Assert-Match -Name "PiiRetentionService.java" -Text $piiRetention -Pattern "piiRetentionAnonymizeCompletedOrders" -Message "must protect scheduled retention with ShedLock"
Assert-Match -Name "JpaPiiRetentionStore.java" -Text $piiRetentionStore -Pattern "setPhoneHmac\(null\)" -Message "must clear user phone blind index on erasure"
Assert-Match -Name "JpaPiiRetentionStore.java" -Text $piiRetentionStore -Pattern "setReceiverPhoneHmac\(null\)" -Message "must clear order phone blind index on erasure"
Assert-Match -Name "docs/security/ws8.md" -Text $docs -Pattern "InnoDB TDE" -Message "must document production database TDE"
Assert-Match -Name "docs/security/ws8.md" -Text $docs -Pattern "Backup jobs must encrypt" -Message "must document encrypted backups"
Assert-Match -Name "docs/security/ws8.md" -Text $docs -Pattern "PIPL/GDPR" -Message "must document compliance audit posture"
Assert-Match -Name "docs/security/ws8.md" -Text $docs -Pattern "APP_PII_BACKFILL_ENABLED=true" -Message "must document the controlled plaintext backfill sequence"
Assert-Match -Name "docs/security/ws8.md" -Text $docs -Pattern "GET /api/stats/audit-trace" -Message "must document audit trace lookup for compliance investigations"

Assert-Match -Name "deploy/nginx/monkeyshop.conf" -Text $nginx -Pattern "client_body_timeout\s+10s;" -Message "must set body timeout for slowloris defense"
Assert-Match -Name "deploy/nginx/monkeyshop.conf" -Text $nginx -Pattern "client_header_timeout\s+10s;" -Message "must set header timeout for slowloris defense"
Assert-Match -Name "deploy/nginx/monkeyshop.conf" -Text $nginx -Pattern "limit_rate\s+512k;" -Message "must throttle slow clients"
Assert-Match -Name "deploy/nginx/monkeyshop.conf" -Text $nginx -Pattern "location = /api/\.env" -Message "must trap API honeypot probes at the edge"
Assert-Match -Name "deploy/nginx/monkeyshop.conf" -Text $nginx -Pattern "location = /admin/secret" -Message "must trap admin honeypot probes at the edge"

Assert-Match -Name "helm/monkeyshop/values.yaml" -Text $helmValues -Pattern "APP_AUTH_CAPTCHA_PROVIDER:\s+turnstile" -Message "must deploy Turnstile by default"
Assert-Match -Name "helm/monkeyshop/values.yaml" -Text $helmValues -Pattern "APP_PII_KEY_PROVIDER:\s+vault-transit" -Message "must deploy Vault Transit PII key release"
Assert-Match -Name "helm/monkeyshop/values.yaml" -Text $helmValues -Pattern "APP_PII_ALLOW_PLAINTEXT_READ:\s+`"false`"" -Message "must disable plaintext PII reads"
Assert-Match -Name "helm/monkeyshop/values.yaml" -Text $helmValues -Pattern "APP_PII_BACKFILL_ENABLED:\s+`"false`"" -Message "must deploy legacy PII backfill disabled by default"
Assert-Match -Name "helm/monkeyshop/values-staging.yaml" -Text $helmStaging -Pattern "APP_TURNSTILE_EXPECTED_HOSTNAME:\s+staging\.monkeyshop\.example\.com" -Message "must bind staging Turnstile tokens to the staging hostname"
Assert-Match -Name "helm/monkeyshop/values-staging.yaml" -Text $helmStaging -Pattern "APP_PII_VAULT_TRANSIT_KEY:\s+monkeyshop-pii-staging" -Message "must use staging PII key release"
Assert-Match -Name "helm/monkeyshop/values-prod.yaml" -Text $helmProd -Pattern "APP_TURNSTILE_EXPECTED_HOSTNAME:\s+monkeyshop\.example\.com" -Message "must bind prod Turnstile tokens to the production hostname"
Assert-Match -Name "helm/monkeyshop/values-prod.yaml" -Text $helmProd -Pattern "APP_PII_VAULT_TRANSIT_KEY:\s+monkeyshop-pii-prod" -Message "must use production PII key release"
Assert-Match -Name "helm/monkeyshop/values.yaml" -Text $helmValues -Pattern "APP_PII_VAULT_TOKEN" -Message "must source Vault token through ExternalSecret"
Assert-Match -Name "helm/monkeyshop/values.yaml" -Text $helmValues -Pattern "APP_PII_VAULT_AES_CIPHERTEXT" -Message "must source wrapped AES DEK through ExternalSecret"
Assert-Match -Name "helm/monkeyshop/values.yaml" -Text $helmValues -Pattern "APP_PII_VAULT_HMAC_CIPHERTEXT" -Message "must source wrapped HMAC key through ExternalSecret"
Assert-Match -Name "helm/monkeyshop/values.yaml" -Text $helmValues -Pattern "APP_PII_VAULT_PREVIOUS_AES_CIPHERTEXTS" -Message "must source wrapped historical AES keys through ExternalSecret"
Assert-Match -Name "CI workflow" -Text $workflow -Pattern "verify-ws8-security\.ps1" -Message "must run the WS8 security gate in CI"

Assert-NotMatch -Name "docs/security/ws8.md" -Text $docs -Pattern "APP_PII_AES_KEY_BASE64`\s+and`\s+APP_PII_HMAC_KEY_BASE64.*production" -Message "must not document env-key production operation"

if ($RunMaven) {
    Invoke-CheckedCommand -Name "focused WS8 Maven tests" -Arguments @(
        "mvn",
        "-Ddependency-check.skip=true",
        "-Dtest=$MavenTestPattern",
        "test"
    )
}

if ($Failures.Count -gt 0) {
    Write-Host "WS8 security gate failed:" -ForegroundColor Red
    foreach ($failure in $Failures) {
        Write-Host " - $failure" -ForegroundColor Red
    }
    exit 1
}

Write-Host "WS8 security gate completed successfully."
