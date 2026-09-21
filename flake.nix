{
  description = "watch-proximity: Smartwatch Proximity Screen Lock & Security Guard for Linux";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils, ... }:
    let
      supportedSystems = [ "x86_64-linux" "aarch64-linux" ];
    in
    flake-utils.lib.eachSystem supportedSystems (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};

        extensionUuid = "watch-proximity@mambuco";

        extension = pkgs.stdenv.mkDerivation {
          pname = "gnome-shell-extension-watch-proximity";
          version = "1.0.0";
          src = ./extension;

          installPhase = ''
            runHook preInstall
            extdir=$out/share/gnome-shell/extensions/${extensionUuid}
            mkdir -p "$extdir"
            cp -r * "$extdir/"
            runHook postInstall
          '';

          passthru.extensionUuid = extensionUuid;
        };

        daemon = pkgs.writers.writePython3Bin "watch-proximity" {
          libraries = [ ];
          flakeIgnore = [ "E501" "E265" "F541" "E226" ];
          makeWrapperArgs = [
            "--prefix"
            "PATH"
            ":"
            (pkgs.lib.makeBinPath [
              pkgs.bluez
              pkgs.systemd
              pkgs.glib
              pkgs.dbus
              pkgs.pipewire
              pkgs.wireplumber
              pkgs.android-tools
              pkgs.sound-theme-freedesktop
            ])
          ];
        } (builtins.readFile ./daemon/watch-proximity.py);
      in
      {
        packages = {
          default = daemon;
          watch-proximity = daemon;
          extension = extension;
        };

        apps = {
          default = flake-utils.lib.mkApp { drv = daemon; };
        };

        devShells.default = pkgs.mkShell {
          packages = [
            daemon
            pkgs.python3
            pkgs.python3Packages.flake8
            pkgs.bluez
            pkgs.android-tools
          ];
        };
      }) // {
        homeManagerModules.default = import ./nix/home-manager.nix;
        homeManagerModules.watch-proximity = import ./nix/home-manager.nix;
      };
}
