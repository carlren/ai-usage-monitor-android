# Security

Please report security issues privately through GitHub's security advisory
feature rather than opening a public issue.

This app expects its configured endpoint to contain display-safe quota data. Never
embed API keys or bearer tokens in the application, and never expose raw upstream
responses containing personal or account metadata through an unauthenticated URL.
