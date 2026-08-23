resource "aws_secretsmanager_secret" "internal_api_token" {
  name                    = "${local.name}/internal-api-token"
  recovery_window_in_days = 0

  tags = { Name = "${local.name}/internal-api-token" }
}

resource "aws_secretsmanager_secret_version" "internal_api_token" {
  secret_id     = aws_secretsmanager_secret.internal_api_token.id
  secret_string = var.internal_api_token
}
