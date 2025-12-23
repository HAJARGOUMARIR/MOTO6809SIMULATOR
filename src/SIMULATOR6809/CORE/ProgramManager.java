package SIMULATOR6809.CORE;

import javax.swing.JOptionPane;
import javax.swing.table.DefaultTableModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

/** ProgrammeManager : GESTIONNAIRE DE PROGRAMME */

public class ProgramManager {
    private final CPU cpu;
    private final CPUView cpuView;
    private final InstructionExecutor executor;
    private final DefaultTableModel romModel;
    private LabelManager labelManager =new LabelManager(); ;
    private List<String> programLines;
    private int currentLine;
    private boolean programLoaded;
    private Stack<CPUState> stateHistory;
    private static final int MAX_HISTORY = 100;


    private static class CPUState {
        final int a, b, x, y, u, s, pc, dp, cc;
        final int lineNumber;

        CPUState(CPU cpu, int lineNumber) {
            this.a = cpu.getA();
            this.b = cpu.getB();
            this.x = cpu.getX();
            this.y = cpu.getY();
            this.u = cpu.getU();
            this.s = cpu.getS();
            this.pc = cpu.getPC();
            this.dp = cpu.getDP();
            this.cc = cpu.getCC();
            this.lineNumber = lineNumber;
        }

        void restore(CPU cpu) {
            cpu.setA(a);
            cpu.setB(b);
            cpu.setX(x);
            cpu.setY(y);
            cpu.setU(u);
            cpu.setS(s);
            cpu.setPC(pc);
            cpu.setDP(dp);
            cpu.setCC(cc);
        }
    }

    public ProgramManager(CPU cpu, CPUView cpuView,
                          DefaultTableModel ramModel, DefaultTableModel romModel) {
        this.cpu = cpu;
        this.cpuView = cpuView;
        this.romModel = romModel;
        this.executor = new InstructionExecutor(cpu, ramModel, romModel);
        this.programLines = new ArrayList<>();
        this.stateHistory = new Stack<>();
        this.currentLine = 0;
        this.programLoaded = false;
    }

    public boolean loadProgram(String sourceCode) {
        if (sourceCode == null || sourceCode.trim().isEmpty()) {
            showError("Programme vide", "Le code source est vide");
            return false;
        }

        programLines.clear();
        stateHistory.clear();

        String[] lines = sourceCode.split("\\r?\\n");
        int validLineCount = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            if (line.isEmpty()) {
                continue;
            }

            if (line.startsWith(";")) {
                continue;
            }

            int commentIndex = line.indexOf(';');
            if (commentIndex > 0) {
                line = line.substring(0, commentIndex).trim();
            }

            programLines.add(line);
            validLineCount++;
        }

        if (validLineCount == 0) {
            showError("Programme vide", "Aucune instruction valide trouvée");
            return false;
        }

        String lastLine = programLines.get(programLines.size() - 1);
        if (!lastLine.equalsIgnoreCase("END")) {
            programLines.add("END");
            showInfo("END ajouté", "La directive END a été ajoutée automatiquement");
        }

        currentLine = 0;
        programLoaded = true;

        showInfo("Programme chargé",
                String.format("%d lignes chargées avec succès", programLines.size()));

        return true;
    }

    // ASSEMBLAGE
    private int lastAssembledBytes = 0;

    public int getLastAssembledBytes() {
        return lastAssembledBytes;
    }

    public boolean assemble() {
        if (!programLoaded) {
            showError("Aucun programme", "Chargez d'abord un programme avec loadProgram()");
            return false;
        }

        try {
            // ÉTAPE 1: Collecter toutes les étiquettes (Première passe)
            if (!collectLabels()) {
                showError("Erreur de collection", "Échec de la collecte des étiquettes");
                return false;
            }

            System.out.println("\n=== PREMIÈRE PASSE: Étiquettes collectées ===");
            labelManager.print();
            System.out.println("=============================================\n");

            clearROM();
            executor.resetRomAddress();
            lastAssembledBytes = 0;

            executor.clearLabels();
            labelManager.getAllLabels().forEach((name, addr) ->
                    executor.registerLabel(name, addr));

            int currentAddress = cpu.getPC();
            int startPC = currentAddress;

            for (int i = 0; i < programLines.size(); i++) {
                String originalLine = programLines.get(i);
                String line = originalLine.trim();

                if (line.startsWith(";") || line.isEmpty()) continue;

                int commentIndex = line.indexOf(';');
                if (commentIndex > 0) {
                    line = line.substring(0, commentIndex).trim();
                }

                if (line.equalsIgnoreCase("END")) {
                    writeROM(executor.getRomAddress(), 0x3F);
                    System.out.println("🏁 END");
                    break;
                }

                if (line.toUpperCase().startsWith("ORG")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        currentAddress = CPU.hexToDecimal(parts[1]) & 0xFFFF;
                        executor.setRomAddress(currentAddress);
                        System.out.println("🔄 ORG $" + CPU.decimalToHex(currentAddress, 4));
                    }
                    continue;
                }


                if (line.endsWith(":")) {
                    System.out.println("🏷️  Étiquette seule: " + line);
                    continue;
                }

                String instruction = line;
                String instructionOnly = line;
                String labelName = null;

                if (line.contains(":")) {
                    String[] labelParts = line.split(":", 2);
                    if (labelParts.length > 0 && !labelParts[0].trim().isEmpty()) {
                        labelName = labelParts[0].trim();
                    }
                    if (labelParts.length > 1 && !labelParts[1].trim().isEmpty()) {
                        instructionOnly = labelParts[1].trim();
                    } else {
                        continue; 
                    }
                }
                else if (InstructionDecoder.hasLabel(line)) {
                    String[] parts = line.split("\\s+", 2);
                    if (parts.length > 0 && InstructionDecoder.hasLabel(line)) {
                        labelName = parts[0];
                        if (parts.length > 1) {
                            instructionOnly = parts[1];
                        } else {
                            continue;
                        }
                    }
                }

                InstructionDecoder.DecodedInstruction instr =
                        InstructionDecoder.decode(instructionOnly);

                if (instr == null) {
                    System.out.println("⚠️  Ligne ignorée: " + line);
                    continue;
                }

                if (instr.mode == InstructionDecoder.AddressingMode.RELATIVE) {
                    String targetLabel = instr.operand.trim();
                    Integer targetAddr = labelManager.getAddress(targetLabel);

                    if (targetAddr == null) {
                        showError("Étiquette non trouvée",
                                "Ligne " + (i+1) + ": '" + targetLabel + "' non définie");
                        return false;
                    }

                    int instructionSize = executor.computeInstructionSize(instr);
                    int nextPC = currentAddress + instructionSize;
                    int displacement = targetAddr - nextPC;

                    if (instr.operation.startsWith("L")) {
                        if (displacement < -32768 || displacement > 32767) {
                            showError("Déplacement trop grand",
                                    "Ligne " + (i+1) + ": déplacement " + displacement +
                                            " hors limites (-32768..+32767)");
                            return false;
                        }
                        instr = new InstructionDecoder.DecodedInstruction(
                                instr.operation, instr.mode,
                                CPU.decimalToHex(displacement & 0xFFFF, 4));
                    } else {
                        if (displacement < -128 || displacement > 127) {
                            showError("Déplacement trop grand",
                                    "Ligne " + (i+1) + ": déplacement " + displacement +
                                            " hors limites (-128..+127). Utilisez LBxx.");
                            return false;
                        }
                        instr = new InstructionDecoder.DecodedInstruction(
                                instr.operation, instr.mode,
                                CPU.decimalToHex(displacement & 0xFF, 2));
                    }

                    System.out.println("🔄 " + instr.operation + " -> " + targetLabel +
                            " ($" + CPU.decimalToHex(targetAddr, 4) +
                            ") disp=" + displacement);
                }

                try {
                    executor.emitToROM(instr);
                    int size = executor.computeInstructionSize(instr);

                    System.out.println("$" + CPU.decimalToHex(currentAddress, 4) +
                            ": " + instr.operation +
                            (instr.operand.isEmpty() ? "" : " " + instr.operand) +
                            " (" + size + " octets)");

                    currentAddress += size;
                } catch (Exception e) {
                    showError("Erreur", "Ligne " + (i+1) + ": " + e.getMessage());
                    return false;
                }
            }

            lastAssembledBytes = executor.getRomAddress() - startPC;
            System.out.println("\n Assemblage réussi: " + lastAssembledBytes + " octets");
            System.out.println("Table des symboles finale:");
            labelManager.print();

            return true;

        } catch (Exception e) {
            showError("Erreur", e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    /**
      Exécute tout le programme d'un coup
     **/
    public void runProgram() {
        if (!programLoaded) {
            showError("Aucun programme", "Chargez d'abord un programme");
            return;
        }

        currentLine = 0;
        stateHistory.clear();
        int instructionCount = 0;

        try {
            while (currentLine < programLines.size()) {
                String line = programLines.get(currentLine);

                if (line.equalsIgnoreCase("END") ||
                        line.toUpperCase().startsWith("SWI")) {
                    break;
                }

                if (isLabelLine(line)) {
                    System.out.println("⏭️ Saut de l'étiquette: " + line);
                    currentLine++;
                    continue;
                }

                int pcBefore = cpu.getPC();
                String instructionOnly = line;
                if (InstructionDecoder.hasLabel(line)) {
                    instructionOnly = InstructionDecoder.removeLabel(line);
                }

                InstructionDecoder.DecodedInstruction instr =
                        InstructionDecoder.decode(instructionOnly);

                if (instr == null) {
                    currentLine++;
                    continue;
                }

                int instructionSize = executor.computeInstructionSize(instr);
                executor.execute(instr);
                instructionCount++;
                int pcAfter = cpu.getPC();
                boolean pcWasModified = (pcAfter != pcBefore);

                if (pcWasModified) {
                    int newLineIndex = findLineIndexByPC(pcAfter);
                    if (newLineIndex != -1) {
                        currentLine = newLineIndex;
                    } else {
                        currentLine++;
                    }
                } else {
                    cpu.setPC((pcBefore + instructionSize) & 0xFFFF);
                    currentLine++;
                }

                if (instructionCount > 10000) {
                    showWarning("Limite atteinte",
                            "10000 instructions exécutées. Arrêt de sécurité.");
                    break;
                }
            }

            updateDisplay();
            showInfo("Exécution terminée",
                    String.format("%d instructions exécutées", instructionCount));

        } catch (Exception e) {
            showError("Erreur d'exécution",
                    String.format("Ligne %d: %s\nInstruction: %s",
                            currentLine + 1, e.getMessage(),
                            programLines.get(currentLine)));
        }
    }
   
    public boolean step() {
        if (!programLoaded) {
            showError("Aucun programme", "Chargez d'abord un programme");
            return false;
        }

        if (currentLine >= programLines.size()) {
            showInfo("Fin du programme", "Toutes les instructions ont été exécutées");
            return false;
        }

        String line = programLines.get(currentLine);

        if (line.equalsIgnoreCase("END") ||
                line.toUpperCase().startsWith("SWI")) {
            showInfo("Programme terminé", "Instruction de fin rencontrée");
            return false;
        }

        if (isLabelLine(line)) {
            currentLine++;
            return step();
        }

        try {
            saveState();
            executeLine(line);
            currentLine++;
            int pcBefore = cpu.getPC();
            String instructionOnly = line;
            if (InstructionDecoder.hasLabel(line)) {
                instructionOnly = InstructionDecoder.removeLabel(line);
            }

            InstructionDecoder.DecodedInstruction instr =
                    InstructionDecoder.decode(instructionOnly);

            if (instr == null) {
                throw new Exception("Impossible de décoder l'instruction");
            }

            int instructionSize = executor.computeInstructionSize(instr);
            executor.execute(instr);
            int pcAfter = cpu.getPC();
            boolean pcWasModified = (pcAfter != pcBefore);

            if (pcWasModified) {
                int newLineIndex = findLineIndexByPC(pcAfter);

                if (newLineIndex != -1) {
                    currentLine = newLineIndex;
                
                } else {
                    currentLine++;
                }
            } else {
                cpu.setPC((pcBefore + instructionSize) & 0xFFFF);
                pcAfter = cpu.getPC();
                currentLine++;
            }

            updateDisplay();
            return true;

        } catch (Exception e) {
            showError("Erreur d'exécution",
                    String.format("Ligne %d: %s\nInstruction: %s",
                            currentLine + 1, e.getMessage(), line));
            return false;
        }
    }
    public boolean stepBack() {
        if (stateHistory.isEmpty()) {
            showWarning("Début du programme",
                    "Impossible de revenir en arrière: aucun état sauvegardé");
            return false;
        }

        try {
            CPUState previousState = stateHistory.pop();
            previousState.restore(cpu);
            currentLine = previousState.lineNumber;
            if (currentLine < programLines.size() &&
                    isLabelLine(programLines.get(currentLine))) {
                System.out.println("⏮️  Recul supplémentaire pour éviter l'étiquette");
                if (!stateHistory.isEmpty()) {
                    CPUState prevPrevState = stateHistory.pop();
                    prevPrevState.restore(cpu);
                    currentLine = prevPrevState.lineNumber;
                }
            }
            updateDisplay();
            showInfo("Retour arrière",
                    String.format("État restauré à la ligne %d", currentLine + 1));

            return true;

        } catch (Exception e) {
            showError("Erreur stepBack",
                    "Impossible de restaurer l'état: " + e.getMessage());
            return false;
        }
    }
    public void reset() {
        cpu.reset();
        currentLine = 0;
        stateHistory.clear();
        updateDisplay();

        if (programLoaded) {
            showInfo("Réinitialisation", "Programme prêt à être exécuté");
        }
    }


    private void executeLine(String line) throws Exception {
        if (isLabelLine(line)) {
            return;
        }
        InstructionDecoder.DecodedInstruction instr =
                InstructionDecoder.decode(line);

        if (instr == null) {
            throw new Exception("Impossible de décoder l'instruction");
        }

        executor.execute(instr);

        int instructionSize = executor.computeInstructionSize(instr);
        cpu.setPC((cpu.getPC() + instructionSize) & 0xFFFF);
    }


    private void saveState() {
        if (stateHistory.size() >= MAX_HISTORY) {
            stateHistory.remove(0); 
        }

        stateHistory.push(new CPUState(cpu, currentLine));
    }

    
    private void updateDisplay() {
        if (cpuView != null) {
            cpuView.updateFromCPU(cpu);
            if (currentLine < programLines.size()) {
                cpuView.setInstruction(programLines.get(currentLine));
            } else {
                cpuView.setInstruction("FIN");
            }

            cpuView.repaint();
        }
    }

    private void clearROM() {
        for (int i = 0; i < romModel.getRowCount(); i++) {
            romModel.setValueAt("FF", i, 1);
        }
    }

    public void writeROM(int address, int value) {
        try {
            if (address >= 0 && address < romModel.getRowCount()) {
                String hexValue = CPU.decimalToHex(value & 0xFF, 2);
                romModel.setValueAt(hexValue, address, 1);
            } else {
                System.err.println("Adresse ROM hors limites: " + address);
            }
        } catch (Exception e) {
            System.err.println("Erreur écriture ROM @" +
                    Integer.toHexString(address) + ": " + e.getMessage());
        }
    }

    private void showError(String title, String message) {
        JOptionPane.showMessageDialog(null, message, title,
                JOptionPane.ERROR_MESSAGE);
    }

    private void showWarning(String title, String message) {
        JOptionPane.showMessageDialog(null, message, title,
                JOptionPane.WARNING_MESSAGE);
    }

    private void showInfo(String title, String message) {
        JOptionPane.showMessageDialog(null, message, title,
                JOptionPane.INFORMATION_MESSAGE);
    }


    public List<String> getProgramLines() {
        return new ArrayList<>(programLines);
    }

    public boolean isProgramLoaded() {
        return programLoaded;
    }

    public InstructionExecutor getExecutor() {
        return executor;
    }

    private boolean collectLabels() {
        if (!programLoaded) {
            return false;
        }

        labelManager.clear();
        int currentAddress = cpu.getPC(); 

        for (int i = 0; i < programLines.size(); i++) {
            String line = programLines.get(i);

            if (line.trim().isEmpty()) continue;

            if (line.trim().startsWith(";")) continue;

            int commentIndex = line.indexOf(';');
            if (commentIndex > 0) {
                line = line.substring(0, commentIndex).trim();
            }

            if (line.equalsIgnoreCase("END")) {
                break;
            }

            String label = InstructionDecoder.extractLabel(line);
            if (label != null && !label.isEmpty()) {
                labelManager.addLabel(label, currentAddress);
            }

            if (!line.toUpperCase().startsWith("ORG")) {
                String instructionOnly = InstructionDecoder.removeLabel(line);

                if (!instructionOnly.trim().isEmpty() &&
                        !instructionOnly.trim().equalsIgnoreCase("END")) {

                    InstructionDecoder.DecodedInstruction instr =
                            InstructionDecoder.decode(instructionOnly);

                    if (instr != null) {
                        int size = executor.computeInstructionSize(instr);
                        currentAddress += size;
                    } else {
                        currentAddress += 1; 
                        currentAddress += 1;
                    }
                }
            } else {
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    String hexAddr = parts[1].replace("$", "").replace("#", "");
                    try {
                        currentAddress = Integer.parseInt(hexAddr, 16) & 0xFFFF;
                    } catch (NumberFormatException e) {
                    }
                }
            }
        }

        return labelManager.getLabelCount() >= 0; 
    }
    private boolean isLabelLine(String line) {
        if (line == null || line.trim().isEmpty()) {
            return false;
        }

        String trimmed = line.trim();

        if (trimmed.endsWith(":")) {
            return true;
        }

        if (InstructionDecoder.hasLabel(trimmed)) {
            return true;
        }

        return false;
    }
    private int findLineIndexByPC(int pcAddress) {
        int address = 0;
        boolean orgFound = false;

        for (String line : programLines) {
            line = line.trim();
            if (line.toUpperCase().startsWith("ORG")) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    try {
                        String addrStr = parts[1].replace("$", "").trim();
                        address = Integer.parseInt(addrStr, 16);
                        orgFound = true;
                        break;
                    } catch (Exception e) {
                    }
                }
            }
        }

        if (!orgFound) {
            address = cpu.getPC();
        }

        for (int i = 0; i < programLines.size(); i++) {
            String line = programLines.get(i).trim();
            if (line.isEmpty() || line.startsWith(";")) {
                continue;
            }

            if (line.toUpperCase().startsWith("ORG")) {
                continue;
            }

            if (line.equalsIgnoreCase("END")) {
                if (address == pcAddress) {
                    return i;
                }
                break;
            }

            String instructionOnly = line;
            if (InstructionDecoder.hasLabel(line)) {
                instructionOnly = InstructionDecoder.removeLabel(line);
                if (instructionOnly.trim().isEmpty()) {
                    continue;
                }
            }

            if (address == pcAddress) {
                return i;
            }

            try {
                InstructionDecoder.DecodedInstruction instr =
                        InstructionDecoder.decode(instructionOnly);
                if (instr != null) {
                    int size = executor.computeInstructionSize(instr);
                    address += size;
                }
            } catch (Exception e) {
                address += 1; 
            }
        }

        return -1; 
    }
}
