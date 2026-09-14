# Security

Please report security issues privately through GitHub's security advisory
feature rather than opening a public issue.

This app expects its configured usage endpoint to contain display-safe quota data.
Never expose raw upstream responses containing personal or account metadata through
an unauthenticated URL.

Cadence API credentials must be provisioned into the app's private
`files/cadence.properties` file. They must not be committed, placed in BuildConfig,
or compiled into the APK.
