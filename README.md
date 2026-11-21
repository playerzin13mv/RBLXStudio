# RBLXStudio

Versão do Roblox Studio para celular (Android), feita em Kotlin + OpenGL ES.

## O que tem na v0.2
- Viewport 3D com Select / Move / Scale / Rotate, Undo / Redo, Copy / Paste / Duplicate
- **Play / Stop próprios**: personagem anda (joystick à esquerda), olha (arrastar à direita) e pula (botão); peças sem Anchored caem; Stop volta a cena ao que era
- Salvar / Abrir lugares (File), CanCollide, Focus (aba VIEW)
- Ícones vetoriais no lugar dos emojis e ícone do app (logo do Roblox Studio)
- **Login com conta do Roblox** (OAuth 2.0 + PKCE)

## Configurar o login com Roblox
1. Abra https://create.roblox.com/dashboard/credentials (aba **OAuth 2.0 Apps**) e crie um app.
2. Em **Permissions** marque os escopos `openid` e `profile`.
3. Em **Redirect URLs** adicione: `rblxstudio://oauth`
4. No app, toque em **Entrar** (canto superior direito), cole o **Client ID** (e o **Client Secret**, se o app tiver) e toque em **Entrar**.

O app fica em modo privado (só você e até 10 usuários) enquanto não for enviado para revisão do Roblox.

## Build
O GitHub Actions gera o APK a cada push (`.github/workflows/build.yml`).
