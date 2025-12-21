package SIMULATOR6809.CORE;

import javax.swing.SwingUtilities;

class Main {

    public static void main(String[] args) {
        // On lance l'interface graphique sur le thread d'événements de Swing (EDT)
        SwingUtilities.invokeLater(() -> {
            try {
                // Initialisation du dashboard principal
                new DASHBOARD();
            } catch (Exception e) {
                e.printStackTrace();
                System.err.println("Erreur lors du lancement du Dashboard : " + e.getMessage());
            }
        });
    }
}