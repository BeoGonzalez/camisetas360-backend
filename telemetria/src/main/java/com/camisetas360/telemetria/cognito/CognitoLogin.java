package com.camisetas360.telemetria.cognito;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminAddUserToGroupRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminConfirmSignUpRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminUpdateUserAttributesRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthenticationResultType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CognitoIdentityProviderException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ConfirmSignUpRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.SignUpRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.SignUpResponse;

/**
 * Login, refresh, registro y confirmacion con usuario (email) y contrasena contra el user pool.
 *
 * <p>Los tokens de USER_PASSWORD_AUTH no traen scopes personalizados: cada microservicio concede sus
 * scopes de forma implicita y autoriza por {@code cognito:groups}. El registro deja al usuario en
 * {@code grupoPorDefecto} (CUSTOMER), nunca en un grupo privilegiado; ADMIN y CATALOG_MANAGER se
 * asignan a mano con {@code admin-add-user-to-group}.
 */
public class CognitoLogin {

    static final Set<String> GRUPOS_PRIVILEGIADOS = Set.of("ADMIN", "CATALOG_MANAGER");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]{1,255}\\.[^@\\s]{2,}$");

    /** Error de Cognito -> status HTTP y mensaje. Los mensajes de login no revelan si el usuario existe. */
    private static final Map<String, Resultado> ERRORES = Map.ofEntries(
            Map.entry("NotAuthorizedException", Resultado.error(401, "Credenciales invalidas")),
            Map.entry("UserNotFoundException", Resultado.error(401, "Credenciales invalidas")),
            Map.entry("UserNotConfirmedException", Resultado.error(403, "El usuario no ha confirmado su email")),
            Map.entry("PasswordResetRequiredException", Resultado.error(403, "Debe restablecer su contrasena")),
            Map.entry("UsernameExistsException", Resultado.error(409, "Ya existe una cuenta con ese email")),
            Map.entry("InvalidPasswordException", Resultado.error(400,
                    "La contrasena no cumple la politica (min. 8, mayuscula, minuscula y numero)")),
            Map.entry("InvalidParameterException", Resultado.error(400, "Datos invalidos")),
            Map.entry("CodeMismatchException", Resultado.error(400, "Codigo invalido")),
            Map.entry("ExpiredCodeException", Resultado.error(400, "Codigo expirado")),
            Map.entry("TooManyRequestsException", Resultado.error(429, "Demasiadas solicitudes, intente mas tarde")),
            Map.entry("LimitExceededException", Resultado.error(429, "Demasiados intentos, intente mas tarde")),
            Map.entry("TooManyFailedAttemptsException", Resultado.error(429, "Demasiados intentos, intente mas tarde")));

    /** Respuesta de una accion: status HTTP y cuerpo JSON (como mapa). */
    public record Resultado(int status, Map<String, Object> cuerpo) {
        public static Resultado error(int status, String mensaje) {
            return new Resultado(status, Map.of("error", mensaje, "status", status));
        }
    }

    private final CognitoIdentityProviderClient cognito;
    private final String userPoolId;
    private final String clientId;
    private final String grupoPorDefecto;
    private final boolean autoConfirmar;

    public CognitoLogin(CognitoIdentityProviderClient cognito, String userPoolId, String clientId,
                        String grupoPorDefecto, boolean autoConfirmar) {
        this.cognito = cognito;
        this.userPoolId = requerido(userPoolId, "USER_POOL_ID");
        this.clientId = requerido(clientId, "CLIENT_ID");
        this.grupoPorDefecto = grupoPorDefecto == null ? "" : grupoPorDefecto.trim();
        this.autoConfirmar = autoConfirmar;
    }

    /** Ejecuta {@code accion} (login|refresh|register|confirm); traduce los errores de Cognito a HTTP. */
    public Resultado ejecutar(String accion, Map<String, Object> datos) {
        try {
            return switch (accion) {
                case "login" -> login(datos);
                case "refresh" -> refresh(datos);
                case "register" -> registrar(datos);
                case "confirm" -> confirmar(datos);
                default -> Resultado.error(404, "Ruta no encontrada");
            };
        } catch (CognitoIdentityProviderException e) {
            String codigo = e.awsErrorDetails() == null ? "" : e.awsErrorDetails().errorCode();
            return ERRORES.getOrDefault(codigo, Resultado.error(500, "Error de autenticacion"));
        }
    }

    Resultado login(Map<String, Object> datos) {
        String email = texto(datos, 256, "email", "username");
        String password = texto(datos, 256, "password");
        if (email == null || password == null) return Resultado.error(400, "email y password son obligatorios");
        return tokens(cognito.initiateAuth(InitiateAuthRequest.builder().clientId(clientId).authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                .authParameters(Map.of("USERNAME", email.toLowerCase(), "PASSWORD", password)).build()), null);
    }

    Resultado refresh(Map<String, Object> datos) {
        String refreshToken = texto(datos, 8192, "refreshToken");
        if (refreshToken == null) return Resultado.error(400, "refreshToken es obligatorio");
        return tokens(cognito.initiateAuth(InitiateAuthRequest.builder().clientId(clientId).authFlow(AuthFlowType.REFRESH_TOKEN_AUTH)
                .authParameters(Map.of("REFRESH_TOKEN", refreshToken)).build()), refreshToken);
    }

    Resultado registrar(Map<String, Object> datos) {
        String email = texto(datos, 256, "email", "username");
        String password = texto(datos, 256, "password");
        if (email == null || password == null) return Resultado.error(400, "email y password son obligatorios");
        if (!EMAIL.matcher(email).matches()) return Resultado.error(400, "email invalido");
        String usuario = email.toLowerCase();
        String nombre = texto(datos, 256, "name", "nombre");
        String nombreFinal = nombre == null ? usuario.substring(0, usuario.indexOf('@')) : nombre;

        SignUpResponse alta = cognito.signUp(SignUpRequest.builder().clientId(clientId).username(usuario).password(password)
                .userAttributes(atributo("email", usuario), atributo("name", nombreFinal)).build());
        boolean confirmado = Boolean.TRUE.equals(alta.userConfirmed());
        if (autoConfirmar && !confirmado) {
            cognito.adminConfirmSignUp(AdminConfirmSignUpRequest.builder().userPoolId(userPoolId).username(usuario).build());
            cognito.adminUpdateUserAttributes(AdminUpdateUserAttributesRequest.builder().userPoolId(userPoolId)
                    .username(usuario).userAttributes(atributo("email_verified", "true")).build());
            confirmado = true;
        }
        if (!grupoPorDefecto.isEmpty() && !GRUPOS_PRIVILEGIADOS.contains(grupoPorDefecto)) {
            cognito.adminAddUserToGroup(AdminAddUserToGroupRequest.builder().userPoolId(userPoolId).username(usuario)
                    .groupName(grupoPorDefecto).build());
        }
        var cuerpo = new LinkedHashMap<String, Object>();
        cuerpo.put("userSub", alta.userSub());
        cuerpo.put("email", usuario);
        cuerpo.put("confirmed", confirmado);
        return new Resultado(201, cuerpo);
    }

    Resultado confirmar(Map<String, Object> datos) {
        String email = texto(datos, 256, "email", "username");
        String codigo = texto(datos, 16, "code", "codigo");
        if (email == null || codigo == null) return Resultado.error(400, "email y code son obligatorios");
        cognito.confirmSignUp(ConfirmSignUpRequest.builder().clientId(clientId).username(email.toLowerCase())
                .confirmationCode(codigo).build());
        return new Resultado(200, Map.of("email", email.toLowerCase(), "confirmed", true));
    }

    private static Resultado tokens(InitiateAuthResponse respuesta, String refreshAnterior) {
        AuthenticationResultType auth = respuesta.authenticationResult();
        if (auth == null) {
            // Desafios (NEW_PASSWORD_REQUIRED, MFA...) no se soportan en este flujo.
            return Resultado.error(403, "Se requiere un paso adicional de autenticacion: " + respuesta.challengeNameAsString());
        }
        var cuerpo = new LinkedHashMap<String, Object>();
        cuerpo.put("accessToken", auth.accessToken());
        cuerpo.put("idToken", auth.idToken());
        cuerpo.put("refreshToken", auth.refreshToken() != null ? auth.refreshToken() : refreshAnterior);
        cuerpo.put("expiresIn", auth.expiresIn());
        cuerpo.put("tokenType", auth.tokenType() != null ? auth.tokenType() : "Bearer");
        return new Resultado(200, cuerpo);
    }

    private static AttributeType atributo(String nombre, String valor) {
        return AttributeType.builder().name(nombre).value(valor).build();
    }

    private static String texto(Map<String, Object> datos, int maximo, String... claves) {
        for (String clave : claves) {
            if (datos.get(clave) instanceof String valor && !valor.isBlank()) {
                String limpio = valor.trim();
                return limpio.length() > maximo ? limpio.substring(0, maximo) : limpio;
            }
        }
        return null;
    }

    private static String requerido(String valor, String nombre) {
        if (valor == null || valor.isBlank()) throw new IllegalStateException("Falta la variable de entorno " + nombre);
        return valor.trim();
    }
}
