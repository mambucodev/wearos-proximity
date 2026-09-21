{ config, lib, pkgs, ... }:

with lib;

let
  cfg = config.security.pam.watch-proximity;

  pamScript = pkgs.writers.writePython3Bin "pam-watch-proximity" {
    libraries = [ ];
    flakeIgnore = [ "E501" "E265" "F541" "E226" ];
  } (builtins.readFile ../daemon/pam-watch-proximity);
in
{
  options.security.pam.watch-proximity = {
    enable = mkEnableOption "watch-proximity PAM passwordless auto-unlock for sudo";

    package = mkOption {
      type = types.package;
      default = pamScript;
      description = "The pam-watch-proximity authentication verification package.";
    };
  };

  config = mkIf cfg.enable {
    environment.systemPackages = [ cfg.package ];

    security.pam.services.sudo = {
      rules.auth.watch-proximity = {
        order = 11930; # Positioned immediately before pam_fprintd (order 11940)
        control = "[success=done default=ignore]";
        modulePath = "${pkgs.pam}/lib/security/pam_exec.so";
        args = [ "quiet" "${cfg.package}/bin/pam-watch-proximity" ];
      };
    };
  };
}
