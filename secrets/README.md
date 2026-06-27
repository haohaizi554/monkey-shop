# Encrypted Secrets

Use SOPS with age for local and deployment secret material.

Plaintext secret files under this directory are ignored by Git. Only encrypted
`*.enc.yaml`, `*.enc.yml`, and `*.enc.json` files may be committed.

Set the recipient out of band before encrypting:

```powershell
$env:SOPS_AGE_RECIPIENTS = "age1..."
sops --encrypt secrets/monkeyshop.yaml > secrets/monkeyshop.enc.yaml
```

Keep the private age key outside the repository. Runtime values still enter the
application through environment variables, Docker secrets, External Secrets, or
the Kubernetes secret store in the target environment.
