{{- define "monkeyshop.podTemplate" -}}
{{- if and (eq .Values.global.environment "prod") (not .Values.image.digest) -}}
{{- fail "image.digest is required for prod releases; CI/CD must write the signed image digest before sync" -}}
{{- end -}}
{{- if and (eq .Values.global.environment "prod") (not (contains "@sha256:" .Values.initContainers.waitForMysql.image)) -}}
{{- fail "initContainers.waitForMysql.image must be digest-pinned for prod releases" -}}
{{- end -}}
metadata:
  labels:
    {{- include "monkeyshop.selectorLabels" . | nindent 4 }}
    app.kubernetes.io/component: app
    {{- with .Values.podLabels }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
  annotations:
    checksum/config: {{ include (print $.Template.BasePath "/configmap.yaml") . | sha256sum }}
    {{- if .Values.externalSecret.enabled }}
    checksum/external-secret: {{ include (print $.Template.BasePath "/externalsecret.yaml") . | sha256sum }}
    {{- end }}
    {{- with .Values.podAnnotations }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
spec:
  serviceAccountName: {{ include "monkeyshop.serviceAccountName" . }}
  automountServiceAccountToken: false
  {{- with .Values.image.pullSecrets }}
  imagePullSecrets:
    {{- toYaml . | nindent 4 }}
  {{- end }}
  securityContext:
    {{- toYaml .Values.podSecurityContext | nindent 4 }}
  {{- if .Values.initContainers.waitForMysql.enabled }}
  initContainers:
    - name: wait-for-mysql
      image: {{ .Values.initContainers.waitForMysql.image | quote }}
      imagePullPolicy: IfNotPresent
      command:
        - sh
        - -ec
        - |
          deadline=$((SECONDS + {{ .Values.initContainers.waitForMysql.timeoutSeconds }}))
          until nc -z "$DB_HOST" "$DB_PORT"; do
            if [ "$SECONDS" -ge "$deadline" ]; then
              echo "Timed out waiting for MySQL at $DB_HOST:$DB_PORT" >&2
              exit 1
            fi
            echo "Waiting for MySQL at $DB_HOST:$DB_PORT"
            sleep 2
          done
      env:
        - name: DB_HOST
          value: {{ .Values.mysql.host | quote }}
        - name: DB_PORT
          value: {{ .Values.mysql.port | quote }}
      securityContext:
        allowPrivilegeEscalation: false
        readOnlyRootFilesystem: true
        runAsNonRoot: true
        runAsUser: 1000
        runAsGroup: 1000
        capabilities:
          drop:
            - ALL
      resources:
        {{- toYaml .Values.initContainers.waitForMysql.resources | nindent 8 }}
  {{- end }}
  containers:
    - name: app
      {{- if .Values.image.digest }}
      image: "{{ .Values.image.repository }}@{{ .Values.image.digest }}"
      {{- else }}
      image: "{{ .Values.image.repository }}:{{ .Values.image.tag | default .Chart.AppVersion }}"
      {{- end }}
      imagePullPolicy: {{ .Values.image.pullPolicy }}
      ports:
        - name: http
          containerPort: {{ .Values.containerPort }}
          protocol: TCP
      env:
        - name: JAVA_OPTS
          value: {{ .Values.javaOpts | quote }}
        {{- with .Values.extraEnv }}
        {{- toYaml . | nindent 8 }}
        {{- end }}
      envFrom:
        - configMapRef:
            name: {{ include "monkeyshop.fullname" . }}-config
        - secretRef:
            name: {{ include "monkeyshop.secretName" . }}
        {{- with .Values.extraEnvFrom }}
        {{- toYaml . | nindent 8 }}
        {{- end }}
      securityContext:
        {{- toYaml .Values.securityContext | nindent 8 }}
      resources:
        {{- toYaml .Values.resources | nindent 8 }}
      startupProbe:
        httpGet:
          path: {{ .Values.probes.startup.path }}
          port: http
        initialDelaySeconds: {{ .Values.probes.startup.initialDelaySeconds }}
        periodSeconds: {{ .Values.probes.startup.periodSeconds }}
        failureThreshold: {{ .Values.probes.startup.failureThreshold }}
        timeoutSeconds: {{ .Values.probes.startup.timeoutSeconds }}
      livenessProbe:
        httpGet:
          path: {{ .Values.probes.liveness.path }}
          port: http
        initialDelaySeconds: {{ .Values.probes.liveness.initialDelaySeconds }}
        periodSeconds: {{ .Values.probes.liveness.periodSeconds }}
        failureThreshold: {{ .Values.probes.liveness.failureThreshold }}
        timeoutSeconds: {{ .Values.probes.liveness.timeoutSeconds }}
      readinessProbe:
        httpGet:
          path: {{ .Values.probes.readiness.path }}
          port: http
        initialDelaySeconds: {{ .Values.probes.readiness.initialDelaySeconds }}
        periodSeconds: {{ .Values.probes.readiness.periodSeconds }}
        failureThreshold: {{ .Values.probes.readiness.failureThreshold }}
        timeoutSeconds: {{ .Values.probes.readiness.timeoutSeconds }}
      volumeMounts:
        - name: tmp
          mountPath: /tmp
        - name: logs
          mountPath: /app/logs
        - name: uploads
          mountPath: /data/images
        {{- with .Values.extraVolumeMounts }}
        {{- toYaml . | nindent 8 }}
        {{- end }}
  volumes:
    - name: tmp
      emptyDir: {}
    - name: logs
      emptyDir: {}
    - name: uploads
      {{- if .Values.persistence.enabled }}
      persistentVolumeClaim:
        claimName: {{ default (printf "%s-uploads" (include "monkeyshop.fullname" .)) .Values.persistence.existingClaim }}
      {{- else }}
      emptyDir: {}
      {{- end }}
    {{- with .Values.extraVolumes }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
  {{- with .Values.nodeSelector }}
  nodeSelector:
    {{- toYaml . | nindent 4 }}
  {{- end }}
  {{- with .Values.affinity }}
  affinity:
    {{- toYaml . | nindent 4 }}
  {{- end }}
  {{- with .Values.tolerations }}
  tolerations:
    {{- toYaml . | nindent 4 }}
  {{- end }}
  {{- with .Values.topologySpreadConstraints }}
  topologySpreadConstraints:
    {{- toYaml . | nindent 4 }}
  {{- end }}
{{- end -}}
